package org.entcore.interoperability.transcode;

import io.vertx.core.json.JsonObject;
import org.entcore.common.utils.StringUtils;

/**
 * Traduit un identifiant de service en nom de dossier d'archive, et réciproquement.
 *
 * Ce détail est la raison pour laquelle l'archive ne peut pas servir de format d'échange : ses
 * noms de dossiers sont des libellés TRADUITS. Un paquet émis par une instance anglophone et
 * réinjecté sur une instance française doit donc voir ses dossiers RENOMMÉS au passage — d'où
 * la règle : le niveau Native stocke le serviceId comme nom de dossier, et c'est le sink qui
 * traduit, avec les libellés de l'instance DESTINATAIRE.
 */
public class ArchiveFolderResolver {

    private final JsonObject i18n;

    /** @param i18n le bundle rendu par « portal / getI18n » sur l'instance courante */
    public ArchiveFolderResolver(JsonObject i18n) {
        this.i18n = i18n == null ? new JsonObject() : i18n;
    }

    /**
     * Même règle exactement que FileSystemExportService.addManifestToExport et que
     * AbstractRepositoryEvents.createExportDirectory : le libellé traduit, accents retirés,
     * et repli sur le préfixe de route brut quand la traduction manque.
     */
    public String folderFor(String serviceId) {
        String label = i18n.getString(serviceId);
        if (label == null) {
            label = i18n.getString(serviceId.toLowerCase());
        }
        return StringUtils.stripAccents(label == null ? serviceId : label);
    }
}
