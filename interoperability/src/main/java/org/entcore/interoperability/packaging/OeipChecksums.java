package org.entcore.interoperability.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sommes de contrôle du paquet, au format sha256sum standard.
 *
 * C'est la correction directe du défaut de l'archive, dont la signature RSA charge la clé
 * privée ET la clé publique depuis le même fichier, et qui est donc inutilisable entre deux
 * plateformes. Ici l'intégrité est TOUJOURS vérifiable, par n'importe qui, sans aucune clé.
 * La signature détachée est un mécanisme distinct et facultatif.
 */
public final class OeipChecksums {

    private OeipChecksums() {}

    /**
     * Fichiers exclus du relevé.
     *
     * checksums.sha256 ne peut pas se contenir lui-même ; la signature porte sur lui et lui est
     * donc postérieure ; et le manifeste épingle l'empreinte du relevé, ce qui ferme la chaîne
     * — l'y inclure créerait une dépendance circulaire.
     */
    public static boolean isExcluded(String packagePath) {
        return org.entcore.interoperability.OeipFormat.CHECKSUMS.equals(packagePath)
                || org.entcore.interoperability.OeipFormat.SIGNATURE.equals(packagePath)
                || org.entcore.interoperability.OeipFormat.MANIFEST.equals(packagePath);
    }

    /** Relevé de tous les fichiers du dossier, trié, prêt à être écrit. */
    public static String compute(Path root) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path p : listFiles(root)) {
            String rel = relative(root, p);
            if (isExcluded(rel)) {
                continue;
            }
            lines.add(sha256(p) + "  " + rel);
        }
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static void write(Path root) throws IOException {
        Path target = root.resolve(org.entcore.interoperability.OeipFormat.CHECKSUMS);
        Files.write(target, compute(root).getBytes(StandardCharsets.UTF_8));
    }

    /** @return la liste des anomalies, vide si le paquet est intègre */
    public static List<String> verify(Path root) throws IOException {
        List<String> problems = new ArrayList<>();
        Path file = root.resolve(org.entcore.interoperability.OeipFormat.CHECKSUMS);
        if (!Files.isRegularFile(file)) {
            problems.add("relevé de sommes absent");
            return problems;
        }
        Map<String, String> declared = parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));

        Map<String, Path> present = new LinkedHashMap<>();
        for (Path p : listFiles(root)) {
            String rel = relative(root, p);
            if (!isExcluded(rel)) {
                present.put(rel, p);
            }
        }
        for (String rel : present.keySet()) {
            if (!declared.containsKey(rel)) {
                // Un fichier non couvert est aussi grave qu'un fichier altéré : il a pu être
                // ajouté après coup.
                problems.add("fichier non couvert par le relevé : " + rel);
            }
        }
        for (Map.Entry<String, String> e : declared.entrySet()) {
            Path p = present.get(e.getKey());
            if (p == null) {
                problems.add("le relevé référence un fichier absent : " + e.getKey());
            } else if (!sha256(p).equals(e.getValue())) {
                problems.add("empreinte incorrecte : " + e.getKey());
            }
        }
        Collections.sort(problems);
        return problems;
    }

    public static Map<String, String> parse(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            int sep = line.indexOf("  ");
            if (sep > 0) {
                map.put(line.substring(sep + 2), line.substring(0, sep));
            }
        }
        return map;
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        InputStream in = Files.newInputStream(file);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    public static String sha256(byte[] bytes) {
        return hex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** Parcours déterministe : l'ordre conditionne la reproductibilité du paquet. */
    public static List<Path> listFiles(Path root) throws IOException {
        final List<Path> files = new ArrayList<>();
        collect(root, files);
        Collections.sort(files);
        return files;
    }

    private static void collect(Path dir, List<Path> acc) throws IOException {
        java.io.File[] children = dir.toFile().listFiles();
        if (children == null) {
            return;
        }
        for (java.io.File c : children) {
            if (c.isDirectory()) {
                collect(c.toPath(), acc);
            } else {
                acc.add(c.toPath());
            }
        }
    }
}
