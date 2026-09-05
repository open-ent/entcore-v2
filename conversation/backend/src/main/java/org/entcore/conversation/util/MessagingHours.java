/*
 * Horaires d'utilisation de la messagerie.
 *
 * Cette classe n'est plus qu'une façade : la lecture, le cache et la règle vivent dans
 * {@link org.entcore.common.utils.SpaceOpeningHours} (entcore common), partagée avec les
 * autres espaces soumis aux mêmes horaires — blog et forum. Le module conversation en reste
 * la SOURCE D'ADMINISTRATION (cf. MessagingHoursController), d'où le maintien de cette
 * façade : les appels existants (ConversationController, ScheduledMessageSender) gardent
 * leur signature, et la portée « messagerie » leur est appliquée implicitement.
 *
 * Restriction, inchangée : seuls les ÉLÈVES sont soumis aux horaires ; hors plage l'envoi
 * est bloqué (lecture seule). Les autres profils ne sont jamais limités.
 */
package org.entcore.conversation.util;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.entcore.common.utils.SpaceOpeningHours;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class MessagingHours {

    public static final String COLLECTION = SpaceOpeningHours.COLLECTION;
    public static final String GLOBAL_ID = SpaceOpeningHours.GLOBAL_ID;
    public static final String STUDENT_PROFILE = SpaceOpeningHours.STUDENT_PROFILE;

    /** Portée appliquée par cette façade. */
    private static final String SCOPE = SpaceOpeningHours.SCOPE_MESSAGING;

    private static final MessagingHours INSTANCE = new MessagingHours();

    private final SpaceOpeningHours delegate = SpaceOpeningHours.getInstance();

    public static MessagingHours getInstance() { return INSTANCE; }

    /** À appeler une fois au démarrage : charge le cache + planifie son rafraîchissement. */
    public void init(Vertx vertx, JsonObject conf) { delegate.init(vertx, conf); }

    /** Recharge le cache depuis Mongo (global + surcharges par établissement). */
    public void reload() { delegate.reload(); }

    /** Horaire effectif d'un établissement : sa surcharge si elle existe, sinon le défaut global. */
    public JsonObject effective(String structureId) { return delegate.effective(structureId); }

    public JsonObject getGlobal() { return delegate.getGlobal(); }

    public JsonObject getStructureOverride(String structureId) {
        return delegate.getStructureOverride(structureId);
    }

    public ZoneId zone() { return delegate.zone(); }

    public boolean isFeatureEnabled() { return delegate.isFeatureEnabled(); }

    /**
     * L'élève peut-il envoyer un message maintenant ? Règle : seuls les élèves sont
     * restreints ; ils sont autorisés dès qu'AU MOINS UN de leurs établissements est
     * ouvert (le plus permissif, pour ne pas bloquer un élève multi-établissements).
     */
    public boolean isSendAllowed(String profile, List<String> structures, ZonedDateTime now) {
        return delegate.isWriteAllowed(SCOPE, profile, structures, now);
    }

    public boolean isSendAllowed(String profile, List<String> structures) {
        return delegate.isWriteAllowed(SCOPE, profile, structures);
    }

    /** Statut destiné au bandeau client : ouvert/fermé + l'horaire applicable. */
    public JsonObject statusFor(String profile, List<String> structures) {
        return delegate.statusFor(SCOPE, profile, structures);
    }
}
