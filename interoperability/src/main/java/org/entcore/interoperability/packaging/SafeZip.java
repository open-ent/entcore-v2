package org.entcore.interoperability.packaging;

/**
 * Garde-fous de dézippage.
 *
 * Un paquet OEIP vient de l'extérieur de la plateforme : il doit être traité comme une entrée
 * hostile. Ces contrôles s'appliquent AVANT toute écriture métier, et avant même de reconstruire
 * un zip d'archive pour le niveau Native.
 */
public final class SafeZip {

    private SafeZip() {}

    /** Au-delà, on refuse : un paquet légitime n'a pas des dizaines de milliers d'entrées. */
    public static final int DEFAULT_MAX_ENTRIES = 200_000;

    /** Rapport de décompression au-delà duquel on suspecte une bombe zip. */
    public static final int DEFAULT_MAX_RATIO = 200;

    public static final long DEFAULT_MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;

    /**
     * Un nom d'entrée doit être un chemin relatif, sans remontée, sans racine, sans antislash
     * et sans caractère de contrôle.
     *
     * @return null si le nom est acceptable, sinon le motif du refus
     */
    public static String rejectEntryName(String name) {
        if (name == null || name.isEmpty()) {
            return "nom d'entrée vide";
        }
        if (name.indexOf('\\') >= 0) {
            return "antislash interdit dans un nom d'entrée : " + name;
        }
        if (name.startsWith("/") || name.startsWith("./") || name.length() > 1 && name.charAt(1) == ':') {
            return "chemin absolu interdit : " + name;
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20) {
                return "caractère de contrôle interdit dans : " + name;
            }
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                return "remontée de répertoire interdite : " + name;
            }
        }
        return null;
    }

    public static boolean isEntryNameSafe(String name) {
        return rejectEntryName(name) == null;
    }

    /**
     * @param compressed   taille compressée déclarée
     * @param uncompressed taille décompressée déclarée
     * @return null si le rapport est acceptable, sinon le motif du refus
     */
    public static String rejectRatio(long compressed, long uncompressed, int maxRatio) {
        if (uncompressed < 0 || compressed < 0) {
            // Taille inconnue : elle devra être plafonnée pendant la lecture du flux.
            return null;
        }
        if (compressed == 0) {
            return null;
        }
        long ratio = uncompressed / compressed;
        if (ratio > maxRatio) {
            return "rapport de décompression suspect (" + ratio + " > " + maxRatio + ")";
        }
        return null;
    }
}
