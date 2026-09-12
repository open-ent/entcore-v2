package org.entcore.interoperability.spi;

import java.nio.file.Path;

/**
 * Ce dont un mapper dispose pour décrire son service.
 *
 * Deux familles de mappers coexistent, et le contexte les sert toutes les deux : ceux qui lisent
 * la base directement — l'annuaire, faute d'export d'archive — et ceux qui transcodent la charge
 * utile produite par l'export d'archive, ce qui évite de réécrire l'extraction déjà faite par
 * chaque module.
 */
public class OeipExportContext {

    private final String scopeUserId;
    private final String locale;
    private final String sourceSystem;
    private final Path nativeFolder;
    private final String nativeFolderName;
    private final boolean includeBinaries;

    public OeipExportContext(String scopeUserId, String locale, String sourceSystem,
                             Path nativeFolder, String nativeFolderName, boolean includeBinaries) {
        this.scopeUserId = scopeUserId;
        this.locale = locale;
        this.sourceSystem = sourceSystem;
        this.nativeFolder = nativeFolder;
        this.nativeFolderName = nativeFolderName;
        this.includeBinaries = includeBinaries;
    }

    public String getScopeUserId() { return scopeUserId; }
    public String getLocale() { return locale; }
    public String getSourceSystem() { return sourceSystem; }

    /** Dossier de la charge utile d'archive, ou {@code null} si le module n'en a pas produit. */
    public Path getNativeFolder() { return nativeFolder; }

    /** Nom traduit de ce dossier chez l'émetteur. */
    public String getNativeFolderName() { return nativeFolderName; }

    public boolean isIncludeBinaries() { return includeBinaries; }
}
