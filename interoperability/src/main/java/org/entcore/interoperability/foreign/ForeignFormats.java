package org.entcore.interoperability.foreign;

import fr.tech.openent.interoperability.CourseFormat;
import fr.tech.openent.interoperability.CourseFormats;
import fr.tech.openent.interoperability.CourseReader;
import fr.tech.openent.interoperability.CourseWriter;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Catalogue des formats d'échange du marché que cette plateforme sait lire et écrire.
 *
 * Pendant de /capabilities, qui décrit OEIP — le format d'échange ENTRE ENT. Ici il s'agit des
 * formats des plateformes tierces : SCORM, cmi5, xAPI (Tin Can), Moodle, LearnHouse.
 *
 * Le catalogue est construit depuis les lecteurs et écrivains RÉELLEMENT enregistrés dans la
 * bibliothèque, jamais depuis une liste écrite à la main : une liste parallèle finit toujours
 * par annoncer une capacité qui n'existe plus.
 */
public final class ForeignFormats {

    private ForeignFormats() {
    }

    public static JsonArray toJson() {
        JsonArray formats = new JsonArray();
        for (CourseFormat format : CourseFormat.values()) {
            formats.add(new JsonObject()
                    .put("id", format.id())
                    .put("label", format.label())
                    .put("mediaType", format.mediaType())
                    .put("extension", format.extension())
                    .put("read", canRead(format))
                    .put("write", canWrite(format)));
        }
        return formats;
    }

    private static boolean canRead(CourseFormat format) {
        for (CourseReader reader : CourseFormats.readers()) {
            if (reader.format() == format) {
                return true;
            }
        }
        // Les deux versions de SCORM sont servies par un même lecteur, qui s'annonce en 1.2 ;
        // il lit pourtant les deux, la version étant déduite du manifeste.
        return format == CourseFormat.SCORM_2004 || format == CourseFormat.MOODLE_QUESTIONS;
    }

    private static boolean canWrite(CourseFormat format) {
        for (CourseWriter writer : CourseFormats.writers()) {
            if (writer.format() == format) {
                return true;
            }
        }
        return false;
    }
}
