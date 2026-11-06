#!/usr/bin/env python3
"""
Fabrique un pack pédagogique au format OEIP 1.1 (profil « pack ») à partir d'un dossier source.

    python3 oeip_pack.py <dossier source> <sortie.oeip>

Dossier source :
    pack.json            {"id": "urn:openent:pack:…", "version", "title", "description",
                          "language", "framework", "frameworkUri", "educationLevels", "license",
                          "sourceSystem", "icon": "icon.svg"}
    icon.svg             icône du pack (SVG, PNG ou WebP, 256 Kio au plus), facultative : les
                         applications la montrent à côté des ressources qui viennent du pack
    resources/*.json     une ressource par fichier :
                         {"key": "sequence", "service": "wiki", "title", "description",
                          "alignments": [{"item": <CFItem>, "code": "C2.MAT…"}],
                          "body": {"mediaType": "text/html", "content": "…"},   (facultatif)
                          "document": {…document natif de l'application…},
                          "file": "fiches/x.pdf", "mediaType": "application/pdf"}  (binaire, facultatif)
                         Un document peut citer une autre ressource du pack par « oeip-pack:<clé> »
                         (l'enregistrement d'une dictée dans un sujet d'exercices) : écrit
                         « oeip-ref:<globalId> » dans le paquet, l'installation le remplace par
                         l'adresse de la ressource installée.

L'identifiant global d'une ressource est dérivé du pack et de sa clé : une nouvelle version du pack
désigne donc la MÊME ressource, et l'installer la met à jour au lieu de la dupliquer.

Le paquet est reproductible : fichiers triés, horodatages figés, sérialisation JSON stable. Le
relevé de sommes suit exactement celui d'OeipChecksums (lignes « <sha256>  <chemin> » triées).
"""
import hashlib
import json
import os
import re
import sys
import zipfile
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMAS = os.path.join(HERE, "..", "src", "main", "resources", "oeip", "schemas", "1.1")
VERSION = "1.1"
ICON_TYPES = {".svg": "image/svg+xml", ".png": "image/png", ".webp": "image/webp"}
ICON_MAX = 256 * 1024
FIXED_ZIP_TIME = (2026, 1, 1, 0, 0, 0)
# Dans un document natif, « oeip-pack:<clé> » désigne une autre ressource du même pack.
REF = re.compile(r"oeip-pack:([A-Za-z0-9._~-]+)")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def dump(obj):
    return (json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=False) + "\n").encode("utf-8")


def slug(text):
    return re.sub(r"[^A-Za-z0-9._~-]+", "-", text).strip("-")


def build(source, output):
    pack = json.load(open(os.path.join(source, "pack.json"), encoding="utf-8"))
    system = pack.pop("sourceSystem", "ent-scolaire.fr")
    generated = pack.pop("generatedAt", "2026-11-03T00:00:00Z")
    pack_slug = slug(pack["id"].split(":")[-1])

    files = {}
    if pack.get("icon"):
        # Même plafond et mêmes types qu'à l'installation (PackPackage) : un pack refusé là doit
        # l'être dès sa fabrication.
        name = pack["icon"]
        ext = os.path.splitext(name)[1].lower()
        assert ext in ICON_TYPES, f"icône {name} : SVG, PNG ou WebP attendu"
        data = open(os.path.join(source, name), "rb").read()
        assert len(data) <= ICON_MAX, f"icône {name} : {len(data)} octets, {ICON_MAX} au plus"
        href = f"pack/icon{ext}"
        files[href] = data
        pack["icon"] = {"href": href, "mediaType": ICON_TYPES[ext], "sha256": sha256(data)}
    resources = [json.load(open(os.path.join(source, "resources", name), encoding="utf-8"))
                 for name in sorted(os.listdir(os.path.join(source, "resources"))) if name.endswith(".json")]
    global_ids = {r["key"]: f"urn:oeip:{VERSION}:resource:{system}:{pack_slug}.{slug(r['key'])}" for r in resources}

    def cite(match):
        # Référence à une autre ressource du pack : l'installation la remplace par son adresse.
        assert match.group(1) in global_ids, f"oeip-pack:{match.group(1)} : aucune ressource de cette clé"
        return "oeip-ref:" + global_ids[match.group(1)]

    items_by_service = defaultdict(list)
    for r in resources:
        service = r["service"]
        item = {
            "globalId": global_ids[r["key"]],
            "sourceSystem": system,
            "serviceId": service,
            "resourceType": f"urn:oeip:restype:{service}.{r.get('type', service)}",
            "title": r["title"],
        }
        if r.get("description"):
            item["description"] = r["description"]
        item["language"] = pack.get("language", "fr")
        item["state"] = "published"
        if r.get("file"):
            # Binaire (fiche PDF…) : copié dans le paquet, désigné par body.href et son empreinte.
            data = open(os.path.join(source, r["file"]), "rb").read()
            href = f"resources/{service}/content/{slug(r['key'])}/{os.path.basename(r['file'])}"
            files[href] = data
            item["body"] = {"mediaType": r.get("mediaType", "application/pdf"), "href": href, "sha256": sha256(data)}
        elif r.get("body"):
            item["body"] = r["body"]
        if r.get("alignments"):
            item["alignments"] = [{k: v for k, v in a.items() if k in ("framework", "item", "code")}
                                  for a in r["alignments"]]
        if r.get("document") is not None:
            document = REF.sub(cite, json.dumps(r["document"], ensure_ascii=False))
            item["extensions"] = {service: json.loads(document)}
        items_by_service[service].append(item)

    services = []
    for service, items in sorted(items_by_service.items()):
        files[f"resources/{service}/resources.json"] = dump({
            "oeipVersion": VERSION, "sourceSystem": system, "generatedAt": generated,
            "serviceId": service, "dataset": "resources", "items": items})
        services.append({"id": service, "normalized": True, "native": False, "fidelity": "full",
                         "counts": {"resources": len(items)}, "paths": {"core": f"resources/{service}"}})

    schema_lines = []
    for name in sorted(os.listdir(SCHEMAS)):
        data = open(os.path.join(SCHEMAS, name), "rb").read()
        files[f"schemas/{VERSION}/{name}"] = data
        schema_lines.append(f"{sha256(data)}  schemas/{VERSION}/{name}")
    bundle = sha256(("".join(l + "\n" for l in sorted(schema_lines))).encode("utf-8"))

    files["oeip-manifest.json"] = dump({
        "format": "open-ent-interoperability-package",
        "oeipVersion": VERSION,
        "profile": "pack",
        "levels": {"core": True, "cc": False, "native": False},
        "source": {"sourceSystem": system, "product": "open-ent"},
        "generatedAt": generated,
        "scope": {"type": "platform", "refs": []},
        "services": services,
        "integrity": {"algorithm": "sha256", "checksumsFile": "checksums.sha256"},
        "schemaBundle": {"path": f"schemas/{VERSION}", "sha256": bundle},
        "pack": pack,
    })

    checksums = sorted(f"{sha256(data)}  {path}" for path, data in files.items())
    files["checksums.sha256"] = "".join(l + "\n" for l in checksums).encode("utf-8")

    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(files):
            info = zipfile.ZipInfo(path, FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, files[path])
    count = sum(len(v) for v in items_by_service.values())
    print(f"{output} : pack {pack['id']} {pack['version']}, {count} ressource(s), "
          f"services {', '.join(sorted(items_by_service))}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    build(sys.argv[1], sys.argv[2])
