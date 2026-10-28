import { clientFilter, locationOf, searchQuery } from "./search";
import { WorkspaceElement } from "./types";

const el = (p: Partial<WorkspaceElement> & { deleted?: boolean }): WorkspaceElement => ({ _id: "x", name: "n", eType: "file", eParent: null, owner: "u", ...p });

describe("clientFilter", () => {
  const list = [el({ _id: "1", name: "Texte.docx", ownerName: "Ada" }), el({ _id: "2", name: "Mon texte.docx", ownerName: "Alan" })];
  it("garde les noms qui commencent par le critère, sans tenir compte de la casse", () => {
    expect(clientFilter(list, "TE").map((e) => e._id)).toEqual(["1"]);
  });
  it("cherche aussi dans le propriétaire", () => expect(clientFilter(list, "ala").map((e) => e._id)).toEqual(["2"]));
  it("un critère vide ne filtre rien", () => expect(clientFilter(list, "  ")).toEqual(list));
});

describe("searchQuery", () => {
  const q = (o: Parameters<typeof searchQuery>[0]) => Object.fromEntries(new URLSearchParams(searchQuery(o)));
  it("dans tout l'espace", () => expect(q({ criteria: "a", everywhere: true, tree: "owner", folderId: "f" })).toEqual({ filter: "all", hierarchical: "true", search: "a", includeall: "true" }));
  it("dans un dossier : tous les arbres, limité à son ascendance", () =>
    expect(q({ criteria: "a", everywhere: false, tree: "owner", folderId: "f" })).toMatchObject({ filter: "all", ancestorId: "f" }));
  it("à la racine d'un arbre", () => {
    const r = q({ criteria: " devoir ", everywhere: false, tree: "shared" });
    expect(r.filter).toBe("shared");
    expect(r.search).toBe("devoir");
    expect(r.ancestorId).toBeUndefined();
  });
});

describe("locationOf", () => {
  it("le dossier parent d'abord", () => {
    expect(locationOf(el({ eParent: "p" }))).toBe("/owner/p");
    expect(locationOf(el({ eParent: "p", isShared: true }))).toBe("/shared/p");
  });
  it("sinon l'arbre qui contient l'élément", () => {
    expect(locationOf(el({ isShared: true }))).toBe("/shared");
    expect(locationOf(el({ deleted: true }))).toBe("/trash");
    expect(locationOf(el({ protected: true }))).toBe("/protected");
    expect(locationOf(el({}))).toBe("/owner");
  });
});
