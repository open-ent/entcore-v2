import { emptyKeys } from "./empty";

describe("emptyKeys", () => {
  it("racine de chaque arbre : titre et phrase d'accueil", () => {
    expect(emptyKeys("owner", true)).toEqual({ title: "empty.workspace.title", text: "empty.workspace" });
    expect(emptyKeys("shared", true)).toEqual({ title: "empty.workspace.shared.title", text: "empty.workspace.shared" });
    expect(emptyKeys("protected", true)).toEqual({ title: "empty.workspace.apps.title", text: "empty.workspace.apps" });
  });
  it("sous-dossier : « Ce dossier est vide »", () => {
    for (const tree of ["owner", "shared", "protected"] as const) expect(emptyKeys(tree, false)).toEqual({ title: "empty.workspace.subfolder.title" });
  });
  it("corbeille : racine et sous-dossier", () => {
    expect(emptyKeys("trash", true)).toEqual({ title: "empty.trash2" });
    expect(emptyKeys("trash", false)).toEqual({ title: "empty.trash.subfolder" });
  });
  it("résultats de recherche vides et arbre sans texte propre : message général", () => {
    expect(emptyKeys("owner", true, true)).toEqual({ title: "workspace.react.empty" });
    expect(emptyKeys("public", true)).toEqual({ title: "workspace.react.empty" });
  });
});
