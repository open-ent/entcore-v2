package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;

/**
 * Construit les métadonnées qui accompagnent un paquet : provenance et notice de traitement.
 *
 * La notice de traitement n'est pas une formalité. Un paquet quitte la plateforme et transporte
 * des personnes identifiées, souvent mineures ; le destinataire doit savoir, sans avoir à le
 * demander, à quelle fin ces données lui parviennent, sur quelle base, qui en répond, et combien
 * de temps il peut les conserver.
 *
 * <p>Quand la plateforme n'a pas renseigné son responsable de traitement, la notice est produite
 * quand même, mais elle le DIT. Un paquet muet sur ce point laisserait croire que la question a
 * été traitée.
 */
public class OeipMetaBuilder {

    public static final String PROVENANCE = "META/provenance.json";
    public static final String RGPD = "META/rgpd.json";

    /** Code d'avertissement porté au manifeste quand la plateforme n'a rien renseigné. */
    public static final String WARNING_UNCONFIGURED = "rgpd.unconfigured";

    private final JsonObject rgpdConfig;
    private final String sourceSystem;
    private final String productVersion;

    public OeipMetaBuilder(JsonObject rgpdConfig, String sourceSystem, String productVersion) {
        this.rgpdConfig = rgpdConfig == null ? new JsonObject() : rgpdConfig;
        this.sourceSystem = sourceSystem;
        this.productVersion = productVersion;
    }

    public JsonObject provenance(String jobId, String exportedByGlobalId, String generatedAt) {
        JsonObject json = new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("sourceSystem", sourceSystem)
                .put("exportedAt", generatedAt)
                .put("jobId", jobId)
                .put("toolchain", new JsonObject()
                        .put("product", OeipFormat.NATIVE_PRODUCT)
                        .put("module", "interoperability")
                        .put("version", productVersion));
        if (exportedByGlobalId != null) {
            json.put("exportedBy", new JsonObject().put("globalId", exportedByGlobalId));
        }
        return json;
    }

    /**
     * @param containsMinors true dès qu'une personne du paquet est signalée mineure — le
     *                       destinataire doit le savoir avant d'ouvrir quoi que ce soit
     * @param pseudonymized  une notice ne peut pas prétendre l'anonymat si le paquet ne l'est pas
     */
    public JsonObject rgpd(boolean containsMinors, boolean pseudonymized, long ttlHours) {
        JsonObject json = new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("purpose", rgpdConfig.getString("purpose",
                        "Portabilité des données à la demande de la personne concernée."))
                .put("legalBasis", rgpdConfig.getString("legalBasis",
                        "Article 20 du règlement (UE) 2016/679 — droit à la portabilité."))
                .put("dataCategories", rgpdConfig.getJsonArray("dataCategories",
                        new JsonArray().add("identification").add("scolarite")
                                .add("contenus-pedagogiques").add("contenus-personnels")))
                .put("containsMinors", containsMinors)
                .put("pseudonymized", pseudonymized)
                .put("retention", new JsonObject()
                        .put("packageTtlHours", ttlHours)
                        .put("notice", "Le paquet doit être détruit par le destinataire dès "
                                + "l'import effectué."))
                .put("transferNotice", transferNotice(containsMinors, pseudonymized));

        JsonObject controller = rgpdConfig.getJsonObject("controller");
        JsonObject dpo = rgpdConfig.getJsonObject("dpo");
        if (controller != null) {
            json.put("controller", controller);
        }
        if (dpo != null) {
            json.put("dpo", dpo);
        }
        if (controller == null || dpo == null) {
            // Dire que l'information manque vaut mieux que de laisser croire qu'elle a été donnée.
            json.put("incomplete", new JsonObject()
                    .put("missing", missing(controller, dpo))
                    .put("notice", "Cette plateforme n'a pas renseigné son responsable de "
                            + "traitement ou son délégué à la protection des données. Le "
                            + "destinataire doit les obtenir avant tout traitement."));
        }
        return json;
    }

    public boolean isConfigured() {
        return rgpdConfig.getJsonObject("controller") != null
                && rgpdConfig.getJsonObject("dpo") != null;
    }

    private static JsonArray missing(JsonObject controller, JsonObject dpo) {
        JsonArray array = new JsonArray();
        if (controller == null) {
            array.add("controller");
        }
        if (dpo == null) {
            array.add("dpo");
        }
        return array;
    }

    private static String transferNotice(boolean containsMinors, boolean pseudonymized) {
        if (pseudonymized) {
            return "Ce paquet ne contient pas de donnée permettant d'identifier directement une "
                    + "personne. Les identifiants qu'il porte restent toutefois stables, et un "
                    + "rapprochement avec d'autres sources pourrait lever cet anonymat.";
        }
        String base = "Ce paquet contient des données à caractère personnel. Son acheminement "
                + "doit être chiffré et son stockage limité à la durée de l'import.";
        return containsMinors
                ? base + " Il concerne notamment des personnes mineures."
                : base;
    }
}
