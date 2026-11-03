#!/usr/bin/env python3
"""
Fabrique un pack pédagogique au format OEIP 1.1 (profil « pack ») à partir d'un dossier source.

    python3 oeip_pack.py <dossier source> <sortie.oeip>

Dossier source :
    pack.json            {"id": "urn:openent:pack:…", "version", "title", "description",
                          "language", "framework", "frameworkUri", "educationLevels", "license",
                          "sourceSystem"}
    resources/*.json     une ressource par fichier :
                         {"key": "sequence", "service": "wiki", "title", "description",
                          "alignments": [{"item": <CFItem>, "code": "C2.MAT…"}],
                          "body": {"mediaType": "text/html", "content": "…"},   (facultatif)
                          "document": {…document natif de l'application…},
                          "file": "fiches/x.pdf", "mediaType": "application/pdf"}  (binaire, facultatif)

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
FIXED_ZIP_TIME = (2026, 1, 1, 0, 0, 0)


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
    items_by_service = defaultdict(list)
    for name in sorted(os.listdir(os.path.join(source, "resources"))):
        if not name.endswith(".json"):
            continue
        r = json.load(open(os.path.join(source, "resources", name), encoding="utf-8"))
        service = r["service"]
        item = {
            "globalId": f"urn:oeip:{VERSION}:resource:{system}:{pack_slug}.{slug(r['key'])}",
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
            item["extensions"] = {service: r["document"]}
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
