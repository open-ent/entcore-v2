import { DEFAULT_FLAGS, readFlags } from "./flags";

const docWith = (json?: string) => {
  const doc = document.implementation.createHTMLDocument("t");
  if (json !== undefined) {
    const s = doc.createElement("script");
    s.id = "workspace-flags";
    s.type = "application/json";
    s.textContent = json;
    doc.body.appendChild(s);
  }
  return doc;
};

describe("readFlags", () => {
  it("lit les drapeaux posés par le serveur et complète par les valeurs par défaut", () => {
    const flags = readFlags(docWith('{"enableLool":true,"cacheDocTTl":30}'));
    expect(flags.enableLool).toBe(true);
    expect(flags.cacheDocTTl).toBe(30);
    expect(flags.enableNextcloud).toBe(false);
  });

  it("sans balise ou avec un JSON illisible, tout reste désactivé", () => {
    expect(readFlags(docWith())).toEqual(DEFAULT_FLAGS);
    expect(readFlags(docWith("{pas du json"))).toEqual(DEFAULT_FLAGS);
  });
});
