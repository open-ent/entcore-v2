package org.entcore.interoperability.spi;

import io.vertx.core.json.JsonObject;

/**
 * Ce qu'un service sait réellement faire en OEIP, sur CETTE plateforme.
 *
 * Cet objet est la source unique de /capabilities. Il n'est jamais déduit de la liste des
 * modules déployés : un service déployé mais sans mapper enregistré n'est pas exportable, et
 * doit être refusé explicitement plutôt que produire un dossier vide dans le paquet.
 */
public final class OeipCapability {

    private final String serviceId;
    private final String labelFr;
    private final String labelEn;
    private final String moduleVersion;
    private final boolean exportCore;
    private final boolean exportNative;
    private final boolean importCore;
    private final boolean importNative;
    private final String fidelity;
    private final String notice;

    public OeipCapability(String serviceId, String labelFr, String labelEn, String moduleVersion,
                          boolean exportCore, boolean exportNative,
                          boolean importCore, boolean importNative,
                          String fidelity, String notice) {
        if (serviceId == null || serviceId.isEmpty()) {
            throw new IllegalArgumentException("serviceId requis");
        }
        this.serviceId = serviceId;
        this.labelFr = labelFr;
        this.labelEn = labelEn;
        this.moduleVersion = moduleVersion;
        this.exportCore = exportCore;
        this.exportNative = exportNative;
        this.importCore = importCore;
        this.importNative = importNative;
        this.fidelity = fidelity;
        this.notice = notice;
    }

    public String getServiceId() { return serviceId; }
    public String getFidelity() { return fidelity; }
    public String getNotice() { return notice; }
    public String getModuleVersion() { return moduleVersion; }
    public boolean isExportCore() { return exportCore; }
    public boolean isExportNative() { return exportNative; }
    public boolean isImportCore() { return importCore; }
    public boolean isImportNative() { return importNative; }

    public boolean isExportable() { return exportCore || exportNative; }
    public boolean isImportable() { return importCore || importNative; }

    public JsonObject toJson() {
        JsonObject labels = new JsonObject();
        if (labelFr != null) labels.put("fr", labelFr);
        if (labelEn != null) labels.put("en", labelEn);

        JsonObject json = new JsonObject()
                .put("id", serviceId)
                .put("labels", labels)
                .put("export", new JsonObject().put("core", exportCore).put("native", exportNative))
                .put("import", new JsonObject().put("core", importCore).put("native", importNative))
                .put("fidelity", fidelity);
        if (moduleVersion != null) json.put("moduleVersion", moduleVersion);
        // Toute fidélité dégradée doit être justifiée en clair : c'est ce qui remplace
        // la perte de données silencieuse du format d'archive.
        if (notice != null) json.put("notice", notice);
        return json;
    }
}
