/*
 * Message d'absence automatique : réglages par utilisateur + garde-fous d'envoi.
 *
 * Chaque usager peut déclarer une absence (texte riche, période facultative). Pendant
 * l'absence, tout message qui lui est adressé déclenche une réponse automatique à son
 * expéditeur.
 *
 * Source de vérité : la collection Mongo « conversation.vacation », un document par usager :
 *   { _id: userId, enabled, body (HTML assaini), startDate, endDate (YYYY-MM-DD, facultatifs),
 *     displayName, updatedAt }
 * Le nom affiché est figé à l'enregistrement : la réponse automatique est construite hors
 * requête HTTP, on évite ainsi un aller-retour Neo4j sur le chemin d'envoi.
 *
 * Lecture mise en cache comme pour {@link StudentMessagingExclusions} : seuls les documents
 * actifs sont chargés, rafraîchis périodiquement, et rechargés immédiatement après un
 * enregistrement.
 *
 * Deux garde-fous, exigés par la fonctionnalité :
 *  - ANTI-BOUCLE : une réponse automatique n'en déclenche jamais une autre (elle est insérée
 *    directement en base, sans repasser par le chemin d'envoi) ;
 *  - UNE SEULE RÉPONSE PAR JOUR ET PAR EXPÉDITEUR : journalisée dans la collection
 *    « conversation.vacation.replies » ({ _id: "absent:expediteur", day, at }).
 */
package org.entcore.conversation.util;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VacationMessages {

    public static final String COLLECTION = "conversation.vacation";
    /** Journal « une réponse par jour et par expéditeur ». */
    public static final String REPLIES_COLLECTION = "conversation.vacation.replies";

    /** Au-delà, on ne répond plus : garde-fou contre les envois à de très grands groupes. */
    public static final int DEFAULT_MAX_REPLIES_PER_MESSAGE = 25;

    private static final VacationMessages INSTANCE = new VacationMessages();

    private final MongoDb mongo = MongoDb.getInstance();
    /** userId -> document de réglage (uniquement les absences activées). */
    private final Map<String, JsonObject> byUser = new ConcurrentHashMap<>();
    private boolean featureEnabled = true;
    private ZoneId zone = ZoneId.of("Europe/Paris");
    private int maxRepliesPerMessage = DEFAULT_MAX_REPLIES_PER_MESSAGE;

    public static VacationMessages getInstance() { return INSTANCE; }

    /** À appeler une fois au démarrage du module : charge le cache + planifie son rafraîchissement. */
    public void init(Vertx vertx, JsonObject conf) {
        long refreshMs = 60000L;
        if (conf != null) {
            this.featureEnabled = conf.getBoolean("vacation-enabled", true);
            refreshMs = conf.getLong("vacation-refresh-ms", 60000L);
            this.maxRepliesPerMessage = conf.getInteger("vacation-max-replies-per-message",
                    DEFAULT_MAX_REPLIES_PER_MESSAGE);
            try {
                this.zone = ZoneId.of(conf.getString("vacation-timezone", "Europe/Paris"));
            } catch (RuntimeException e) {
                this.zone = ZoneId.of("Europe/Paris");
            }
        }
        final long refresh = refreshMs;
        // Premier chargement différé (MongoDb peut ne pas avoir encore son EventBus) + timer filet.
        vertx.setTimer(1, id -> reload());
        vertx.setPeriodic(refresh, id -> reload());
    }

    /** Recharge le cache depuis Mongo. Défensif : si MongoDb n'est pas prêt, on réessaiera. */
    public void reload() {
        try {
            doReload();
        } catch (RuntimeException e) {
            // MongoDb pas encore initialisé : on réessaiera au prochain rafraîchissement.
        }
    }

    private void doReload() {
        mongo.find(COLLECTION, new JsonObject().put("enabled", true), msg -> {
            final JsonObject body = msg.body();
            if (body == null || !"ok".equals(body.getString("status"))) return;
            final JsonArray results = body.getJsonArray("results", new JsonArray());
            final Map<String, JsonObject> map = new HashMap<>();
            for (Object o : results) {
                if (!(o instanceof JsonObject)) continue;
                final JsonObject doc = (JsonObject) o;
                final String userId = doc.getString("_id");
                if (userId != null) map.put(userId, doc);
            }
            this.byUser.clear();
            this.byUser.putAll(map);
        });
    }

    public boolean isFeatureEnabled() { return featureEnabled; }

    public int getMaxRepliesPerMessage() { return maxRepliesPerMessage; }

    public ZoneId zone() { return zone; }

    /** Date du jour dans le fuseau de référence, au format YYYY-MM-DD (format des bornes stockées). */
    public String today() {
        return LocalDate.now(zone).toString();
    }

    /**
     * Réglages de cet usager si son absence est ACTIVE aujourd'hui, {@code null} sinon.
     * Une borne absente signifie « pas de limite de ce côté ».
     */
    public JsonObject activeFor(String userId) {
        if (!featureEnabled || userId == null) return null;
        final JsonObject doc = byUser.get(userId);
        return isActive(doc, today()) ? doc : null;
    }

    /** L'absence décrite par ce document court-elle à la date {@code day} (YYYY-MM-DD) ? */
    public static boolean isActive(JsonObject doc, String day) {
        if (doc == null || !doc.getBoolean("enabled", false)) return false;
        final String start = trimToNull(doc.getString("startDate"));
        final String end = trimToNull(doc.getString("endDate"));
        if (start != null && day.compareTo(start) < 0) return false;
        if (end != null && day.compareTo(end) > 0) return false;
        return true;
    }

    /**
     * Réserve le droit d'envoyer UNE réponse automatique de {@code absentUserId} à
     * {@code senderId} aujourd'hui.
     *
     * <p>L'opération est atomique : on tente de poser la date du jour sur un document dont la
     * date est différente. Si le document porte déjà la date du jour, la requête ne correspond
     * à rien et l'upsert retombe sur un insert au même {@code _id}, que Mongo refuse (clé
     * dupliquée) — la réservation échoue, donc on ne répond pas deux fois.</p>
     *
     * @return un futur vrai si la réponse peut être envoyée.
     */
    public Future<Boolean> reserveDailyReply(String absentUserId, String senderId) {
        final Promise<Boolean> promise = Promise.promise();
        final String day = today();
        final JsonObject query = new JsonObject()
                .put("_id", absentUserId + ":" + senderId)
                .put("day", new JsonObject().put("$ne", day));
        final JsonObject modifier = new JsonObject().put("$set", new JsonObject()
                .put("day", day)
                .put("at", System.currentTimeMillis()));
        try {
            mongo.update(REPLIES_COLLECTION, query, modifier, true, false,
                    res -> promise.complete("ok".equals(res.body().getString("status"))));
        } catch (RuntimeException e) {
            promise.complete(false);
        }
        return promise.future();
    }

    /** Statut destiné au bandeau client et à l'écran de paramétrage. */
    public JsonObject statusFor(String userId, JsonObject stored) {
        final String day = today();
        final JsonObject doc = stored != null ? stored : byUser.get(userId);
        return new JsonObject()
                .put("featureEnabled", featureEnabled)
                .put("enabled", doc != null && doc.getBoolean("enabled", false))
                .put("active", isActive(doc, day))
                .put("body", doc != null ? doc.getString("body", "") : "")
                .put("startDate", doc != null ? doc.getString("startDate") : null)
                .put("endDate", doc != null ? doc.getString("endDate") : null);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
