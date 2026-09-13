package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projette la partie pédagogique d'un paquet au profil IMS Common Cartridge 1.3.
 *
 * Cette projection n'est jamais normative : un importeur OEIP lit le manifeste OEIP et lui seul.
 * Elle sert à un autre public — les plateformes d'apprentissage, qui savent lire ce format et
 * ignorent le nôtre.
 *
 * Deux règles gouvernent ce qui est écrit ici.
 *
 * <p>La première : <b>aucune extension propriétaire dans le XML</b>. Le standard ne sanctionne
 * qu'un seul mécanisme d'extension, réservé aux types de ressources enregistrés ; y injecter des
 * éléments d'un autre espace de noms produirait un fichier qui ne passerait ni comme Common
 * Cartridge ni comme OEIP. Le seul pont admis est une métadonnée de ressource portant un
 * identifiant catalogué, doublée d'une table de correspondance publiée côté OEIP.
 *
 * <p>La seconde : <b>l'annuaire n'y figure pas</b>. Le standard n'a aucune notion d'utilisateur,
 * de groupe ni d'inscription. Y faire apparaître une personne serait à la fois invalide et
 * indiscret.
 */
public class ImsManifestWriter {

    private static final String NS_CP = "http://www.imsglobal.org/xsd/imsccv1p3/imscp_v1p1";
    private static final String NS_LOM_RES = "http://ltsc.ieee.org/xsd/imsccv1p3/LOM/resource";
    private static final String NS_LOM_MAN = "http://ltsc.ieee.org/xsd/imsccv1p3/LOM/manifest";
    private static final String NS_XSI = "http://www.w3.org/2001/XMLSchema-instance";

    private static final String SCHEMA_LOCATION =
            NS_CP + " http://www.imsglobal.org/profile/cc/ccv1p3/ccv1p3_imscp_v1p2_v1p0.xsd "
            + NS_LOM_RES + " http://www.imsglobal.org/profile/cc/ccv1p3/LOM/ccv1p3_lomresource_v1p0.xsd "
            + NS_LOM_MAN + " http://www.imsglobal.org/profile/cc/ccv1p3/LOM/ccv1p3_lommanifest_v1p0.xsd";

    /** Catalogue sous lequel l'identifiant d'échange est déclaré dans les métadonnées. */
    public static final String CATALOG = "OEIP";

    private final String title;
    private final String language;

    /** ccResourceIdentifier -> globalId, publiée telle quelle dans le manifeste OEIP. */
    private final Map<String, String> mapping = new LinkedHashMap<String, String>();

    public ImsManifestWriter(String title, String language) {
        this.title = title == null || title.isEmpty() ? "Export Open ENT" : title;
        this.language = language == null || language.isEmpty() ? "fr" : language;
    }

    /**
     * Construit le manifeste à partir des ressources et pièces jointes de niveau Core.
     *
     * @param resourcesByService serviceId -> items de resources.json
     * @param attachmentsByService serviceId -> items de attachments.json
     * @return le XML, ou {@code null} si rien n'est projetable — auquel cas le paquet ne doit pas
     *         déclarer le niveau pédagogique
     */
    public String build(Map<String, JsonArray> resourcesByService,
                        Map<String, JsonArray> attachmentsByService) {
        mapping.clear();

        // Index des pièces jointes, pour rattacher à chaque contenu les fichiers qu'il cite.
        Map<String, JsonObject> attachmentByGlobalId = new LinkedHashMap<String, JsonObject>();
        for (JsonArray items : attachmentsByService.values()) {
            for (int i = 0; i < items.size(); i++) {
                JsonObject a = items.getJsonObject(i);
                attachmentByGlobalId.put(a.getString("globalId"), a);
            }
        }

        List<JsonObject> containers = new ArrayList<JsonObject>();
        List<JsonObject> leaves = new ArrayList<JsonObject>();
        for (JsonArray items : resourcesByService.values()) {
            for (int i = 0; i < items.size(); i++) {
                JsonObject r = items.getJsonObject(i);
                if (r.getJsonObject("body") != null && r.getJsonObject("body").getString("href") != null) {
                    leaves.add(r);
                } else {
                    containers.add(r);
                }
            }
        }
        // Un fichier de l'espace documentaire est un contenu à part entière pour une plateforme
        // d'apprentissage : on le projette aussi.
        List<JsonObject> standaloneFiles = new ArrayList<JsonObject>();
        for (Map.Entry<String, JsonArray> e : attachmentsByService.entrySet()) {
            if (!"workspace".equals(e.getKey())) {
                continue;
            }
            for (int i = 0; i < e.getValue().size(); i++) {
                standaloneFiles.add(e.getValue().getJsonObject(i));
            }
        }

        if (leaves.isEmpty() && standaloneFiles.isEmpty()) {
            // Rien de pédagogique à montrer : mieux vaut ne pas produire un cartouche vide.
            return null;
        }

        StringBuilder resourcesXml = new StringBuilder();
        Map<String, String> refByGlobalId = new LinkedHashMap<String, String>();

        for (JsonObject r : leaves) {
            String globalId = r.getString("globalId");
            String ref = identifierFor(globalId);
            refByGlobalId.put(globalId, ref);
            Set<String> files = new LinkedHashSet<String>();
            String href = r.getJsonObject("body").getString("href");
            files.add(href);
            JsonArray refs = r.getJsonArray("attachmentRefs", new JsonArray());
            for (int i = 0; i < refs.size(); i++) {
                JsonObject a = attachmentByGlobalId.get(refs.getString(i));
                if (a != null && a.getString("path") != null) {
                    files.add(a.getString("path"));
                }
            }
            resourcesXml.append(resourceXml(ref, href, globalId, files));
        }

        for (JsonObject a : standaloneFiles) {
            String globalId = a.getString("globalId");
            String ref = identifierFor(globalId);
            refByGlobalId.put(globalId, ref);
            Set<String> files = new LinkedHashSet<String>();
            files.add(a.getString("path"));
            resourcesXml.append(resourceXml(ref, a.getString("path"), globalId, files));
        }

        StringBuilder items = new StringBuilder();
        // Les conteneurs deviennent des rubriques sans ressource : le standard admet l'entrée
        // purement structurelle, et c'est la seule façon de représenter un blog.
        for (JsonObject container : containers) {
            StringBuilder children = new StringBuilder();
            for (JsonObject leaf : leaves) {
                if (container.getString("globalId").equals(leaf.getString("parentResourceRef"))) {
                    children.append(itemXml(itemId(leaf.getString("globalId")),
                            refByGlobalId.get(leaf.getString("globalId")),
                            leaf.getString("title"), ""));
                }
            }
            items.append(itemXml(itemId(container.getString("globalId")), null,
                    container.getString("title"), children.toString()));
        }
        // Les contenus sans conteneur, et les fichiers, restent accessibles à la racine.
        for (JsonObject leaf : leaves) {
            if (leaf.getString("parentResourceRef") == null) {
                items.append(itemXml(itemId(leaf.getString("globalId")),
                        refByGlobalId.get(leaf.getString("globalId")), leaf.getString("title"), ""));
            }
        }
        for (JsonObject a : standaloneFiles) {
            items.append(itemXml(itemId(a.getString("globalId")),
                    refByGlobalId.get(a.getString("globalId")), a.getString("fileName"), ""));
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<manifest identifier=\"" + identifierFor("manifest").replace("R_", "M_") + "\"\n"
                + "          xmlns=\"" + NS_CP + "\"\n"
                + "          xmlns:lom=\"" + NS_LOM_RES + "\"\n"
                + "          xmlns:lomimscc=\"" + NS_LOM_MAN + "\"\n"
                + "          xmlns:xsi=\"" + NS_XSI + "\"\n"
                + "          xsi:schemaLocation=\"" + SCHEMA_LOCATION + "\">\n"
                + "  <metadata>\n"
                + "    <schema>IMS Common Cartridge</schema>\n"
                + "    <schemaversion>1.3.0</schemaversion>\n"
                + "    <lomimscc:lom>\n"
                + "      <lomimscc:general>\n"
                + "        <lomimscc:title>\n"
                + "          <lomimscc:string language=\"" + escape(language) + "\">"
                + escape(title) + "</lomimscc:string>\n"
                + "        </lomimscc:title>\n"
                + "        <lomimscc:language>" + escape(language) + "</lomimscc:language>\n"
                + "      </lomimscc:general>\n"
                + "    </lomimscc:lom>\n"
                + "  </metadata>\n"
                + "  <organizations>\n"
                + "    <organization identifier=\"O_1\" structure=\"rooted-hierarchy\">\n"
                + "      <item identifier=\"I_root\">\n"
                + items
                + "      </item>\n"
                + "    </organization>\n"
                + "  </organizations>\n"
                + "  <resources>\n"
                + resourcesXml
                + "  </resources>\n"
                + "</manifest>\n";
    }

    /** Table de correspondance à publier dans le manifeste OEIP — le seul pont entre les deux. */
    public JsonArray getCcMapping() {
        JsonArray array = new JsonArray();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            array.add(new JsonObject()
                    .put("ccResourceIdentifier", e.getKey())
                    .put("globalId", e.getValue()));
        }
        return array;
    }

    private String resourceXml(String ref, String href, String globalId, Set<String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <resource identifier=\"").append(ref)
          .append("\" type=\"webcontent\" href=\"").append(escape(href)).append("\">\n")
          .append("      <metadata>\n")
          .append("        <lom:lom>\n")
          .append("          <lom:general>\n")
          .append("            <lom:identifier>\n")
          .append("              <lom:catalog>").append(CATALOG).append("</lom:catalog>\n")
          .append("              <lom:entry>").append(escape(globalId)).append("</lom:entry>\n")
          .append("            </lom:identifier>\n")
          .append("          </lom:general>\n")
          .append("        </lom:lom>\n")
          .append("      </metadata>\n");
        for (String f : files) {
            sb.append("      <file href=\"").append(escape(f)).append("\"/>\n");
        }
        sb.append("    </resource>\n");
        return sb.toString();
    }

    private String itemXml(String id, String ref, String title, String children) {
        return "        <item identifier=\"" + id + "\""
                + (ref == null ? "" : " identifierref=\"" + ref + "\"") + ">\n"
                + "          <title>" + escape(title == null ? "Sans titre" : title) + "</title>\n"
                + children
                + "        </item>\n";
    }

    /** Identifiant XML valide, stable et reproductible, dérivé de l'identifiant d'échange. */
    private String identifierFor(String globalId) {
        String ref = "R_" + sha1Prefix(globalId);
        if (!"manifest".equals(globalId)) {
            mapping.put(ref, globalId);
        }
        return ref;
    }

    private static String itemId(String globalId) {
        return "I_" + sha1Prefix(globalId);
    }

    static String sha1Prefix(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 indisponible", e);
        }
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
