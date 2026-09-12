#!/usr/bin/env bash
# Suite de tests du validateur OEIP.
#
# Un validateur qui accepte un paquet correct ne prouve rien : ce qui compte est
# qu'il REJETTE les paquets fautifs. Chaque cas ci-dessous dégrade le paquet de
# référence d'une seule façon, et doit produire au moins une erreur.
#
# Usage : tools/test-oeip-lint.sh
# Pas de « pipefail » ici : oeip_lint sort volontairement en 1 quand il rejette un paquet,
# ce qui masquerait le succès du grep dans les pipelines ci-dessous.
set -u
cd "$(dirname "$0")/.."

SRC=src/test/resources/oeip/fixtures/minimal-1.0
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
pass=0; fail=0

expect_reject() {
  local name="$1"; shift
  rm -rf "$TMP/case"; cp -r "$SRC" "$TMP/case"
  ( cd "$TMP/case" && eval "$@" ) || true
  local out; out=$(python3 tools/oeip_lint.py lint "$TMP/case" 2>&1)
  if printf '%s' "$out" | grep -q "ERREUR"; then
    echo "  ok       rejeté : $name"; pass=$((pass+1))
  else
    echo "  ÉCHEC    accepté à tort : $name"; fail=$((fail+1))
  fi
}

echo "== le paquet de référence doit être accepté =="
ref_out=$(python3 tools/oeip_lint.py lint "$SRC" 2>&1)
if printf '%s' "$ref_out" | grep -q "conforme"; then
  echo "  ok       accepté : paquet de référence"; pass=$((pass+1))
else
  echo "  ÉCHEC    rejeté à tort : paquet de référence"; fail=$((fail+1))
fi

echo "== les paquets fautifs doivent être rejetés =="
expect_reject "binaire altéré" \
  "printf 'x' >> resources/blog/content/fi/file-0001/squelette.png"
expect_reject "objet absent de l'index d'identifiants" \
  "python3 -c \"import json;d=json.load(open('identifiers.json'));d['entries']=[e for e in d['entries'] if 'post-0001' not in e['globalId']];json.dump(d,open('identifiers.json','w'))\""
expect_reject "identifiant brut résiduel dans un corps HTML" \
  "sed -i 's|oeip:file/urn:oeip:1.0:file:ent.exemple-a.fr:file-0001|/workspace/document/6f2b1a44-0000-4000-8000-000000000201|' resources/blog/content/fi/file-0001/index.html"
expect_reject "entité d'annuaire projetée dans Common Cartridge" \
  "sed -i 's|urn:oeip:1.0:resource:ent.exemple-a.fr:post-0001|urn:oeip:1.0:person:ent.exemple-a.fr:person-0001|' imsmanifest.xml"
expect_reject "fidélité dégradée sans notice explicative" \
  "python3 -c \"import json;d=json.load(open('oeip-manifest.json'));[s.pop('notice',None) for s in d['services']];json.dump(d,open('oeip-manifest.json','w'))\""
expect_reject "service non normalisé déclaré « full »" \
  "python3 -c \"import json;d=json.load(open('oeip-manifest.json'));d['services'][1]['normalized']=False;json.dump(d,open('oeip-manifest.json','w'))\""
expect_reject "href Common Cartridge pointant dans le vide" \
  "sed -i 's|resources/workspace/content/fi/file-0002/rapport.txt|resources/workspace/content/fi/file-0002/absent.txt|g' imsmanifest.xml"
expect_reject "niveau natif déclaré sans nativeFormat" \
  "python3 -c \"import json;d=json.load(open('oeip-manifest.json'));d['levels']['native']=True;json.dump(d,open('oeip-manifest.json','w'))\""
expect_reject "fichier ajouté hors du fichier de sommes" \
  "echo clandestin > resources/blog/clandestin.json"
expect_reject "pseudonymisation combinée au niveau natif" \
  "python3 -c \"import json;d=json.load(open('oeip-manifest.json'));d['levels']['native']=True;d['nativeFormat']={'product':'open-ent','archiveVersion':'6.14.9-patched'};d['options']['pseudonymized']=True;json.dump(d,open('oeip-manifest.json','w'))\""
expect_reject "type de relation hors vocabulaire" \
  "python3 -c \"import json;d=json.load(open('relations.json'));d['items'][0]['type']='teleportedTo';json.dump(d,open('relations.json','w'))\""
expect_reject "JSON illisible dans le paquet" \
  "printf 'pas du json' > relations.json"
expect_reject "manifeste absent" \
  "rm -f oeip-manifest.json"

echo
echo "  bilan : $pass réussis, $fail échoués"
[ "$fail" -eq 0 ]
