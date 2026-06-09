/*
 * Horaires d'utilisation de la messagerie.
 *
 * Source de vérité partagée entre la messagerie classique (ce module) et la
 * messagerie instantanée (module chat-nats) : un collection Mongo « messaging.hours »
 * lu par les deux backends. Un document _id="global" porte le défaut de plateforme
 * (réglé par le super-admin) ; un document _id=<structureId> porte la surcharge d'un
 * établissement (réglée par l'ADML / le chef d'établissement).
 *
 * Restriction : seuls les ÉLÈVES (profil "Student") sont soumis aux horaires ; hors
 * plage, l'envoi est bloqué (lecture seule). Les autres profils ne sont jamais limités.
 *
 * Lecture mise en cache (rafraîchie périodiquement) pour ne pas toucher Mongo sur le
 * chemin d'envoi. Singleton initialisé au démarrage du module.
 */
package org.entcore.conversation.util;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessagingHours {

    public static final String COLLECTION = "messaging.hours";
    public static final String GLOBAL_ID = "global";
    public static final String STUDENT_PROFILE = "Student";

    private static final MessagingHours INSTANCE = new MessagingHours();

    private final MongoDb mongo = MongoDb.getInstance();
    private volatile JsonObject global = null;
    private final Map<String, JsonObject> byStructure = new ConcurrentHashMap<>();
    private ZoneId zone = ZoneId.of("Europe/Paris");
    private boolean featureEnabled = true;

    public static MessagingHours getInstance() { return INSTANCE; }

    /** À appeler une fois au démarrage du module : charge le cache + planifie son rafraîchissement. */
    public void init(Vertx vertx, JsonObject conf) {
        long refreshMs = 60000L;
        if (conf != null) {
            final String tz = conf.getString("messaging-hours-timezone");
            if (tz != null) {
                try { this.zone = ZoneId.of(tz); } catch (RuntimeException ignored) {}
            }
            this.featureEnabled = conf.getBoolean("messaging-hours-enabled", true);
            refreshMs = conf.getLong("messaging-hours-refresh-ms", 60000L);
        }
        // Premier chargement différé + défensif : si MongoDb n'a pas encore son EventBus au
        // moment de l'init, un mongo.find() immédiat lèverait « this.eb null ». On défère au
        // prochain tick et le timer périodique sert de filet.
        final long refresh = refreshMs;
        vertx.setTimer(1, id -> reload());
        vertx.setPeriodic(refresh, id -> reload());
    }

    /** Recharge le cache depuis Mongo (global + surcharges par établissement). Défensif :
     *  si MongoDb n'est pas encore prêt, on ignore — le prochain tick réessaiera. */
    public void reload() {
        try {
            doReload();
        } catch (RuntimeException e) {
            // MongoDb pas encore initialisé : on réessaiera au prochain rafraîchissement.
        }
    }

    private void doReload() {
        mongo.find(COLLECTION, new JsonObject(), msg -> {
            final JsonObject body = msg.body();
            if (body == null || !"ok".equals(body.getString("status"))) return;
            final JsonArray results = body.getJsonArray("results", new JsonArray());
            JsonObject g = null;
            final Map<String, JsonObject> map = new HashMap<>();
            for (Object o : results) {
                if (!(o instanceof JsonObject)) continue;
                final JsonObject doc = (JsonObject) o;
                final String id = doc.getString("_id");
                if (GLOBAL_ID.equals(id)) g = doc;
                else if (id != null) map.put(id, doc);
            }
            this.global = g;
            this.byStructure.clear();
            this.byStructure.putAll(map);
        });
    }

    /** Horaire effectif d'un établissement : sa surcharge si elle existe, sinon le défaut global. */
    public JsonObject effective(String structureId) {
        final JsonObject s = byStructure.get(structureId);
        return (s != null) ? s : global;
    }

    public JsonObject getGlobal() { return global; }

    public JsonObject getStructureOverride(String structureId) { return byStructure.get(structureId); }

    public ZoneId zone() { return zone; }

    public boolean isFeatureEnabled() { return featureEnabled; }

    /** Un horaire (null/désactivé = toujours ouvert) est-il ouvert à l'instant {@code now} ? */
    private boolean isOpen(JsonObject schedule, ZonedDateTime now) {
        if (schedule == null) return true;
        if (!schedule.getBoolean("enabled", false)) return true;
        final JsonArray days = schedule.getJsonArray("days");
        final int dow = now.getDayOfWeek().getValue(); // 1=lundi … 7=dimanche
        if (days != null && !days.isEmpty() && !days.contains(dow)) return false;
        final LocalTime start = parseTime(schedule.getString("start"), LocalTime.MIN);
        final LocalTime end = parseTime(schedule.getString("end"), LocalTime.MAX);
        final LocalTime t = now.toLocalTime();
        return !t.isBefore(start) && t.isBefore(end);
    }

    /**
     * L'élève peut-il envoyer un message maintenant ? Règle : seuls les élèves sont
     * restreints ; ils sont autorisés dès qu'AU MOINS UN de leurs établissements est
     * ouvert (le plus permissif, pour ne pas bloquer un élève multi-établissements).
     */
    public boolean isSendAllowed(String profile, List<String> structures, ZonedDateTime now) {
        if (!featureEnabled) return true;
        if (!STUDENT_PROFILE.equals(profile)) return true;
        if (structures == null || structures.isEmpty()) return isOpen(global, now);
        for (String s : structures) {
            if (isOpen(effective(s), now)) return true;
        }
        return false;
    }

    public boolean isSendAllowed(String profile, List<String> structures) {
        return isSendAllowed(profile, structures, ZonedDateTime.now(zone));
    }

    /** Statut destiné au bandeau client : ouvert/fermé + l'horaire applicable. */
    public JsonObject statusFor(String profile, List<String> structures) {
        final ZonedDateTime now = ZonedDateTime.now(zone);
        final boolean restricted = featureEnabled && STUDENT_PROFILE.equals(profile);
        final boolean open = isSendAllowed(profile, structures, now);
        return new JsonObject()
                .put("open", open)
                .put("restricted", restricted)
                .put("schedule", restricted ? displaySchedule(structures) : null)
                .put("now", now.toString());
    }

    /** Horaire à afficher : le premier établissement avec un horaire activé, sinon le global. */
    private JsonObject displaySchedule(List<String> structures) {
        if (structures != null) {
            for (String s : structures) {
                final JsonObject e = effective(s);
                if (e != null && e.getBoolean("enabled", false)) return e;
            }
        }
        return (global != null && global.getBoolean("enabled", false)) ? global : null;
    }

    private static LocalTime parseTime(String hhmm, LocalTime def) {
        if (hhmm == null) return def;
        try {
            final String[] p = hhmm.split(":");
            return LocalTime.of(Integer.parseInt(p[0].trim()), p.length > 1 ? Integer.parseInt(p[1].trim()) : 0);
        } catch (RuntimeException e) {
            return def;
        }
    }
}
