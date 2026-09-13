package org.entcore.interoperability.spi;

import io.vertx.core.json.JsonObject;

import java.nio.file.Path;

/**
 * Ce dont un importeur sémantique dispose.
 *
 * Le mode d'essai est porté ici plutôt que laissé à l'appréciation de chaque importeur : appliquer
 * des données venues d'ailleurs doit être un acte délibéré, et la règle doit être la même partout.
 */
public class OeipImportContext {

    private final Path packageRoot;
    private final String targetUserId;
    private final String targetUserLogin;
    private final String targetUserName;
    private final boolean dryRun;
    /**
     * Identifiants d'échange déjà repris, et leur équivalent local.
     *
     * Un contenu peut citer un fichier importé par un AUTRE service : cette table se remplit au
     * fil des importeurs, et permet aux suivants de résoudre ce que les précédents ont créé.
     */
    private final java.util.Map<String, String> localByGlobalId =
            new java.util.LinkedHashMap<String, String>();
    private final JsonObject manifest;

    public OeipImportContext(Path packageRoot, String targetUserId, String targetUserLogin,
                             String targetUserName, boolean dryRun, JsonObject manifest) {
        this.packageRoot = packageRoot;
        this.targetUserId = targetUserId;
        this.targetUserLogin = targetUserLogin;
        this.targetUserName = targetUserName == null ? targetUserLogin : targetUserName;
        this.dryRun = dryRun;
        this.manifest = manifest == null ? new JsonObject() : manifest;
    }

    /** Racine du paquet dézippé. */
    public Path getPackageRoot() { return packageRoot; }

    public String getTargetUserId() { return targetUserId; }
    public String getTargetUserLogin() { return targetUserLogin; }
    public String getTargetUserName() { return targetUserName; }

    /** Table des objets déjà repris : identifiant d'échange -> identifiant local créé. */
    public java.util.Map<String, String> getLocalByGlobalId() { return localByGlobalId; }

    /** true = on va jusqu'au bout du raisonnement, mais rien n'est écrit. */
    public boolean isDryRun() { return dryRun; }

    public JsonObject getManifest() { return manifest; }

    /** Autorité émettrice du paquet, utile pour distinguer ses identifiants des nôtres. */
    public String getSourceSystem() {
        return manifest.getJsonObject("source", new JsonObject()).getString("sourceSystem");
    }
}
