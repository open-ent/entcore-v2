#!/usr/bin/env python3
"""
OEIP 1.0 — construction et validation d'un paquet d'échange.

Prototype de référence de l'outil Java org.entcore.interoperability.tools.OeipLint.
Il tient lieu de spécification exécutable : tant que le Java n'existe pas, c'est lui
qui prouve que les schémas et les règles du format sont applicables.

  build <dossier>   met à jour les empreintes dérivées, écrit checksums.sha256, produit le .oeip
  lint  <cible>     valide un dossier de paquet ou un fichier .oeip

Le code de sortie vaut 0 si et seulement si aucune erreur n'est relevée.
"""
import argparse, hashlib, io, json, os, re, sys, zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

SCHEMA_DIR_REL = "schemas/1.0"
CHECKSUMS = "checksums.sha256"
SIGNATURE = "META/signature.json"
UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

# Les binaires sont stockés tels quels ; seul le texte est compressé.
TEXT_SUFFIXES = {".json", ".xml", ".html", ".txt", ".md", ".csv", ".sha256"}


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_json(p: Path):
    return json.loads(p.read_text(encoding="utf-8"))


def dump_json(p: Path, obj):
    # Clés ordonnées et séparateurs fixes : un paquet doit être reproductible et diffable.
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=False) + "\n", encoding="utf-8")


def iter_files(root: Path):
    for p in sorted(root.rglob("*")):
        if p.is_file():
            yield p


def rel(root: Path, p: Path) -> str:
    return p.relative_to(root).as_posix()


def schema_bundle_sha(root: Path) -> str:
    """Empreinte du lot de schémas : sha256 du listing « <sha>  <chemin> » trié."""
    sd = root / SCHEMA_DIR_REL
    lines = [f"{sha256_file(p)}  {rel(root, p)}" for p in iter_files(sd)]
    return hashlib.sha256(("\n".join(sorted(lines)) + "\n").encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------- build

def build(root: Path) -> int:
    errors = []

    # .../src/test/resources/oeip/fixtures/<paquet> -> .../src/main/resources/oeip/schemas/1.0
    schemas_src = root.parents[4] / "main" / "resources" / "oeip" / SCHEMA_DIR_REL
    if not schemas_src.is_dir():
        # Chemin alternatif : exécution depuis un paquet déjà constitué.
        schemas_src = None
    if schemas_src:
        dest = root / SCHEMA_DIR_REL
        dest.mkdir(parents=True, exist_ok=True)
        for s in sorted(schemas_src.glob("*.schema.json")):
            (dest / s.name).write_text(s.read_text(encoding="utf-8"), encoding="utf-8")
        print(f"  schémas embarqués : {len(list(dest.glob('*.schema.json')))} fichiers")

    # 1. empreintes réelles des binaires -> attachments, identifiers, body
    real = {}
    for ds in sorted(root.glob("resources/*/attachments.json")):
        doc = load_json(ds)
        for item in doc["items"]:
            fp = root / item["path"]
            if not fp.is_file():
                errors.append(f"pièce jointe absente : {item['path']}")
                continue
            item["sha256"] = sha256_file(fp)
            item["size"] = fp.stat().st_size
            real[item["globalId"]] = item["sha256"]
        dump_json(ds, doc)

    for ds in sorted(root.glob("resources/*/resources.json")):
        doc = load_json(ds)
        for item in doc["items"]:
            body = item.get("body") or {}
            if "href" in body:
                fp = root / body["href"]
                if not fp.is_file():
                    errors.append(f"corps de ressource absent : {body['href']}")
                else:
                    body["sha256"] = sha256_file(fp)
        dump_json(ds, doc)

    idf = root / "identifiers.json"
    doc = load_json(idf)
    for e in doc["entries"]:
        if e.get("kind") == "file":
            href = e.get("href")
            fp = root / href if href else None
            if fp and fp.is_file():
                e["sha256"] = sha256_file(fp)
            else:
                errors.append(f"identifiers.json : fichier introuvable pour {e['globalId']}")
    dump_json(idf, doc)

    # 2. manifeste : empreinte du lot de schémas
    man_path = root / "oeip-manifest.json"
    man = load_json(man_path)
    man["schemaBundle"]["sha256"] = schema_bundle_sha(root)
    dump_json(man_path, man)

    # 3. checksums.sha256 — couvre tout sauf lui-même et la signature
    lines = []
    for p in iter_files(root):
        r = rel(root, p)
        if r in (CHECKSUMS, SIGNATURE) or r.endswith(".oeip"):
            continue
        lines.append(f"{sha256_file(p)}  {r}")
    (root / CHECKSUMS).write_text("\n".join(sorted(lines)) + "\n", encoding="utf-8")

    # 4. le manifeste épingle l'empreinte du fichier de sommes
    man = load_json(man_path)
    man["integrity"]["checksumsSha256"] = sha256_file(root / CHECKSUMS)
    dump_json(man_path, man)

    # Le manifeste vient de changer : sa ligne dans checksums.sha256 est périmée.
    # On réécrit le fichier de sommes en excluant le manifeste, qui est couvert par
    # l'empreinte du manifeste lui-même via integrity (chaîne de confiance explicite).
    lines = []
    for p in iter_files(root):
        r = rel(root, p)
        if r in (CHECKSUMS, SIGNATURE, "oeip-manifest.json") or r.endswith(".oeip"):
            continue
        lines.append(f"{sha256_file(p)}  {r}")
    (root / CHECKSUMS).write_text("\n".join(sorted(lines)) + "\n", encoding="utf-8")
    man = load_json(man_path)
    man["integrity"]["checksumsSha256"] = sha256_file(root / CHECKSUMS)
    dump_json(man_path, man)

    if errors:
        for e in errors:
            print(f"  ERREUR {e}")
        return 1

    # 5. zip reproductible : entrées triées, horodatage figé, méthode par entrée
    out = root.parent / (root.name + ".oeip")
    with zipfile.ZipFile(out, "w") as z:
        for p in iter_files(root):
            r = rel(root, p)
            if r.endswith(".oeip"):
                continue
            zi = zipfile.ZipInfo(r, date_time=(1980, 1, 1, 0, 0, 0))
            zi.external_attr = 0o644 << 16
            is_text = p.suffix.lower() in TEXT_SUFFIXES
            zi.compress_type = zipfile.ZIP_DEFLATED if is_text else zipfile.ZIP_STORED
            z.writestr(zi, p.read_bytes())
    print(f"  paquet écrit : {out}  ({out.stat().st_size} octets)")
    return 0


# --------------------------------------------------------------------------- lint

class Pkg:
    """Accès uniforme à un paquet, qu'il soit un dossier ou un .oeip."""
    def __init__(self, target: Path):
        self.zip = None
        self.broken = []
        if target.is_dir():
            self.root = target
            self.names = [rel(target, p) for p in iter_files(target)]
        else:
            self.zip = zipfile.ZipFile(target)
            self.root = None
            self.names = [n for n in self.zip.namelist() if not n.endswith("/")]

    def read(self, name: str) -> bytes:
        if self.zip:
            return self.zip.read(name)
        return (self.root / name).read_bytes()

    def exists(self, name: str) -> bool:
        return name in self.names

    def json(self, name: str):
        """Un paquet vient de l'extérieur : un contenu illisible se rapporte, il ne fait pas tomber l'outil."""
        try:
            return json.loads(self.read(name).decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as ex:
            msg = f"{name} : JSON illisible ({ex})"
            if msg not in self.broken:
                self.broken.append(msg)
            return None


def lint(target: Path) -> int:
    errs, warns = [], []
    pkg = Pkg(target)

    def err(m): errs.append(m)
    def warn(m): warns.append(m)

    # --- 1. présence des fichiers normatifs
    if not pkg.exists("oeip-manifest.json"):
        print("  ERREUR oeip-manifest.json absent : ce n'est pas un paquet OEIP")
        return 1
    man = pkg.json("oeip-manifest.json")
    if man is None:
        print("  ERREUR oeip-manifest.json illisible : paquet rejeté")
        return 1

    for req in (CHECKSUMS, "identifiers.json"):
        if not pkg.exists(req):
            err(f"{req} absent")

    levels = man.get("levels", {})
    if levels.get("cc") and not pkg.exists("imsmanifest.xml"):
        err("levels.cc vaut true mais imsmanifest.xml est absent")
    if levels.get("native") and not man.get("nativeFormat"):
        err("levels.native vaut true mais nativeFormat est absent")
    if man.get("options", {}).get("pseudonymized") and levels.get("native"):
        err("pseudonymized et levels.native s'excluent : la charge utile native n'est pas filtrée")

    # --- 2. validation de schéma (niveau Core uniquement)
    try:
        import jsonschema
        from jsonschema import Draft7Validator, RefResolver
    except ImportError:
        warn("jsonschema absent : validation de schéma non exécutée")
        jsonschema = None

    if jsonschema:
        store, missing = {}, False
        for name in pkg.names:
            if name.startswith(SCHEMA_DIR_REL) and name.endswith(".schema.json"):
                s = json.loads(pkg.read(name).decode("utf-8"))
                store[s["$id"]] = s
                store[Path(name).name] = s
        if not store:
            err("aucun schéma embarqué sous schemas/1.0 : le paquet n'est pas auto-descriptif")
            missing = True

        def validate(doc_name, schema_file):
            if not pkg.exists(doc_name) or missing:
                return
            schema = store.get(schema_file)
            if schema is None:
                err(f"schéma embarqué manquant : {schema_file}")
                return
            resolver = RefResolver(base_uri=schema["$id"], referrer=schema, store=store)
            v = Draft7Validator(schema, resolver=resolver)
            doc = pkg.json(doc_name)
            if doc is None:
                return
            for e in sorted(v.iter_errors(doc), key=lambda e: list(e.path)):
                ptr = "/" + "/".join(str(x) for x in e.path)
                err(f"{doc_name}{ptr} : {e.message}")

        validate("oeip-manifest.json", "oeip-manifest-1.0.schema.json")
        validate("identifiers.json", "identifiers-1.0.schema.json")
        validate("relations.json", "relations-1.0.schema.json")
        for n in pkg.names:
            if n.startswith("directory/") and n.endswith(".json"):
                validate(n, "directory-1.0.schema.json")
            if n.startswith("resources/") and n.endswith(".json") and "/content/" not in n:
                validate(n, "resource-1.0.schema.json")

        # le lot de schémas doit correspondre à ce que le manifeste épingle
        lines = sorted(
            f"{hashlib.sha256(pkg.read(n)).hexdigest()}  {n}"
            for n in pkg.names if n.startswith(SCHEMA_DIR_REL)
        )
        got = hashlib.sha256(("\n".join(lines) + "\n").encode("utf-8")).hexdigest()
        if got != man.get("schemaBundle", {}).get("sha256"):
            warn("schemaBundle.sha256 diverge du lot embarqué (un ENT tiers peut légitimement étendre)")

    # --- 3. intégrité : vérifiable sans aucune clé
    if pkg.exists(CHECKSUMS):
        raw = pkg.read(CHECKSUMS).decode("utf-8")
        declared = {}
        for line in raw.splitlines():
            if not line.strip():
                continue
            h, _, name = line.partition("  ")
            declared[name] = h
        covered = {n for n in pkg.names if n not in (CHECKSUMS, SIGNATURE, "oeip-manifest.json")}
        for name in sorted(covered - declared.keys()):
            err(f"fichier non couvert par {CHECKSUMS} : {name}")
        for name in sorted(declared.keys() - covered):
            err(f"{CHECKSUMS} référence un fichier absent : {name}")
        for name in sorted(declared.keys() & covered):
            got = hashlib.sha256(pkg.read(name)).hexdigest()
            if got != declared[name]:
                err(f"empreinte incorrecte : {name}")
        got = hashlib.sha256(pkg.read(CHECKSUMS)).hexdigest()
        if got != man.get("integrity", {}).get("checksumsSha256"):
            err("integrity.checksumsSha256 ne correspond pas au fichier de sommes")

    # --- 4. intégrité référentielle des globalId
    defined, refs = set(), []
    idf = (pkg.json("identifiers.json") if pkg.exists("identifiers.json") else {"entries": []}) or {"entries": []}
    index = {e["globalId"] for e in idf.get("entries", [])}
    for a in idf.get("aliases", []):
        index.update(a.get("sameAs", []))

    def scan(obj, origin):
        if isinstance(obj, dict):
            for k, v in obj.items():
                if k == "globalId" and isinstance(v, str):
                    defined.add(v)
                elif (k.endswith("Ref") or k.endswith("Refs") or k in ("fromRef", "toRef")) and v:
                    for gid in ([v] if isinstance(v, str) else v):
                        refs.append((gid, origin, k))
                else:
                    scan(v, origin)
        elif isinstance(obj, list):
            for it in obj:
                scan(it, origin)

    for n in pkg.names:
        if n.endswith(".json") and "/content/" not in n and not n.startswith(SCHEMA_DIR_REL) \
           and n not in ("identifiers.json", "oeip-manifest.json"):
            scan(pkg.json(n) or {}, n)

    for gid, origin, field in refs:
        if gid not in defined and gid not in index:
            err(f"référence non résolue : {gid} ({origin}, champ {field})")
    for gid in sorted(defined - index):
        err(f"objet absent de identifiers.json : {gid}")

    # les entrées de niveau Core doivent être localisables
    for e in idf.get("entries", []):
        href = e.get("href")
        if e.get("level") == "core" and href:
            path = href.split("#")[0]
            if not pkg.exists(path):
                err(f"identifiers.json : href introuvable {href}")

    # --- 5. Common Cartridge
    if pkg.exists("imsmanifest.xml"):
        NS = "{http://www.imsglobal.org/xsd/imsccv1p3/imscp_v1p1}"
        root = ET.fromstring(pkg.read("imsmanifest.xml"))
        ver = root.findtext(f"{NS}metadata/{NS}schemaversion")
        if ver != "1.3.0":
            err(f"imsmanifest.xml : schemaversion attendu 1.3.0, trouvé {ver}")
        cc_ids = set()
        for res in root.iter(f"{NS}resource"):
            cc_ids.add(res.get("identifier"))
            href = res.get("href")
            if href and not pkg.exists(href):
                err(f"imsmanifest.xml : href de ressource introuvable {href}")
            for f in res.iter(f"{NS}file"):
                fh = f.get("href")
                if fh and not pkg.exists(fh):
                    err(f"imsmanifest.xml : <file href> introuvable {fh}")
        orgs = root.findall(f"{NS}organizations/{NS}organization")
        if len(orgs) > 1:
            err("imsmanifest.xml : Common Cartridge n'admet qu'une seule organisation")
        for o in orgs:
            if o.get("structure") != "rooted-hierarchy":
                err("imsmanifest.xml : structure d'organisation attendue « rooted-hierarchy »")
        for it in root.iter(f"{NS}item"):
            ref = it.get("identifierref")
            if ref and ref not in cc_ids:
                err(f"imsmanifest.xml : item pointant une ressource inconnue {ref}")
        mapped = {m["ccResourceIdentifier"] for m in man.get("ccMapping", [])}
        for missing_id in sorted(cc_ids - mapped):
            err(f"ccMapping : ressource CC {missing_id} sans correspondance OEIP")
        for m in man.get("ccMapping", []):
            if m["globalId"] not in index:
                err(f"ccMapping : globalId inconnu {m['globalId']}")
        # l'annuaire ne doit jamais fuiter dans le XML
        xml_text = pkg.read("imsmanifest.xml").decode("utf-8")
        for kind in (":person:", ":group:", ":org:", ":membership:"):
            if kind in xml_text:
                err(f"imsmanifest.xml contient une entité d'annuaire ({kind}) : "
                    f"Common Cartridge ne modélise pas l'annuaire")

    # --- 6. aucun identifiant brut résiduel dans les contenus
    for n in pkg.names:
        if n.endswith((".html", ".htm")):
            txt = pkg.read(n).decode("utf-8", "replace")
            for u in set(UUID_RE.findall(txt)):
                err(f"{n} : identifiant brut résiduel {u} — toute référence doit être un globalId")

    # --- 7. fidélité déclarée service par service
    for svc in man.get("services", []):
        if svc.get("fidelity") != "full" and not svc.get("notice"):
            err(f"service {svc.get('id')} : fidélité « {svc.get('fidelity')} » sans notice explicative")
        if not svc.get("normalized") and svc.get("fidelity") in ("full", "partial"):
            err(f"service {svc.get('id')} : non normalisé mais déclaré « {svc.get('fidelity')} »")

    errs.extend(pkg.broken)

    for w in warns:
        print(f"  AVERTISSEMENT {w}")
    for e in errs:
        print(f"  ERREUR {e}")
    if not errs:
        print(f"  paquet conforme — {len(pkg.names)} fichiers, "
              f"{len(index)} identifiants, niveaux {levels}")
    return 1 if errs else 0


def main():
    ap = argparse.ArgumentParser(description="OEIP 1.0 — construction et validation")
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build"); b.add_argument("target")
    l = sub.add_parser("lint");  l.add_argument("target")
    a = ap.parse_args()
    t = Path(a.target).resolve()
    print(f"{a.cmd} : {t}")
    sys.exit(build(t) if a.cmd == "build" else lint(t))


if __name__ == "__main__":
    main()
