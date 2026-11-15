package org.entcore.interoperability.packs;

import fr.tech.openent.interoperability.CourseFormat;
import fr.tech.openent.interoperability.CourseFormats;
import fr.tech.openent.interoperability.CourseWriter;
import fr.tech.openent.interoperability.WriteOptions;
import fr.tech.openent.interoperability.model.ConversionReport;
import fr.tech.openent.interoperability.model.Course;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.packaging.OeipPackageReader;
import org.entcore.interoperability.schema.OeipValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Export d'un pack dans un format d'échange du marché : le paquet {@code .oeip} conservé à
 * l'installation est relu (intégrité et schéma compris), projeté en cours neutre
 * ({@link PackCourse}) puis écrit par l'écrivain de la bibliothèque d'interopérabilité.
 *
 * <p>Repartir du paquet plutôt que des ressources installées est voulu : l'export rend le pack tel
 * qu'il a été publié, sans les variantes qu'un établissement a pu en faire.</p>
 */
public final class PackExport {

    private PackExport() {
    }

    /** Fichier produit, nom proposé au téléchargement et ce qui a été perdu en le produisant. */
    public static final class Exported {
        public final Path file;
        public final String fileName;
        public final CourseFormat format;
        public final ConversionReport report;

        Exported(Path file, String fileName, CourseFormat format, ConversionReport report) {
            this.file = file;
            this.fileName = fileName;
            this.format = format;
            this.report = report;
        }
    }

    /**
     * Formats dans lesquels un pack s'exporte : tous ceux que la bibliothèque sait écrire. Ceux qui
     * ne portent que des questions ({@code questionsOnly}) laissent la séquence, les cartes et les
     * fiches de côté.
     */
    public static JsonArray formats() {
        final JsonArray formats = new JsonArray();
        for (CourseWriter writer : CourseFormats.writers()) {
            final CourseFormat format = writer.format();
            formats.add(new JsonObject()
                    .put("id", format.id())
                    .put("label", format.label())
                    .put("extension", format.extension())
                    .put("questionsOnly", format.isAssessmentOnly()));
        }
        return formats;
    }

    /**
     * @param packageFile paquet {@code .oeip} du pack
     * @param workDir     répertoire de travail, propre à cet export, que l'appelant efface ensuite
     */
    public static Exported export(Path packageFile, Path workDir, OeipValidator validator, CourseFormat format,
                                  WriteOptions options, long maxUncompressed)
            throws IOException, PackPackage.PackException {
        final JsonObject descriptor = PackPackage.read(
                OeipPackageReader.open(packageFile, workDir.resolve("unzipped"), maxUncompressed), validator);
        final ConversionReport report = new ConversionReport();
        if (format.isAssessmentOnly()) {
            report.info("pack.export.questions.only", "Le format " + format.label() + " ne porte que des "
                    + "questions : la séquence, les cartes mentales et les fiches du pack ne sont pas exportées.", null);
        }
        final Course course = PackCourse.convert(descriptor, report);
        try {
            final Path target = workDir.resolve("export" + format.extension());
            Files.deleteIfExists(target);
            CourseFormats.write(course, target, format, options, report);
            return new Exported(target, fileName(descriptor.getJsonObject("pack", new JsonObject()), format),
                    format, report);
        } finally {
            course.close();
        }
    }

    /**
     * {@code urn:openent:pack:3e-accords} en 1.0.1 vers Common Cartridge →
     * {@code 3e-accords-1.0.1-imscc.imscc} ; le format est toujours nommé, deux formats partageant
     * l'extension {@code .zip}.
     */
    static String fileName(JsonObject pack, CourseFormat format) {
        final String id = pack.getString("id", "pack");
        final String version = pack.getString("version");
        final String base = id.substring(id.lastIndexOf(':') + 1) + (version == null ? "" : "-" + version)
                + "-" + format.id();
        return base.replaceAll("[^A-Za-z0-9._-]", "_") + format.extension();
    }
}
