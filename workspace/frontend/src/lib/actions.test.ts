import { availableActions, descendantIds, downloadUrl, forbiddenTargets, renamedWithExtension } from "./actions";
import { WorkspaceElement } from "./types";

const el = (p: Partial<WorkspaceElement>): WorkspaceElement => ({ _id: "x", name: "n", eType: "file", eParent: null, owner: "u", ...p });

describe("availableActions", () => {
  const one = [el({})];
  const two = [el({ _id: "a" }), el({ _id: "b" })];
  it("sans sélection, aucune action", () => expect(availableActions("owner", [])).toEqual([]));
  it("Mes documents : renommer seulement pour un élément", () => {
    expect(availableActions("owner", one)).toEqual(["rename", "copy", "move", "trash", "download"]);
    expect(availableActions("owner", two)).toEqual(["copy", "move", "trash", "download"]);
  });
  it("corbeille : restaurer et supprimer définitivement", () => expect(availableActions("trash", one)).toEqual(["restore", "delete"]));
  it("partagés : copier et télécharger", () => expect(availableActions("shared", one)).toEqual(["copy", "download"]));
});

describe("dossiers", () => {
  const folders = [
    el({ _id: "a", eType: "folder", eParent: null }),
    el({ _id: "b", eType: "folder", eParent: "a" }),
    el({ _id: "c", eType: "folder", eParent: "b" }),
    el({ _id: "d", eType: "folder", eParent: null }),
  ];
  it("liste les descendants à tous les niveaux", () => expect([...descendantIds(folders, "a")].sort()).toEqual(["b", "c"]));
  it("interdit un dossier et ses sous-dossiers comme destination", () => {
    expect([...forbiddenTargets(folders, [folders[0]])].sort()).toEqual(["a", "b", "c"]);
    expect(forbiddenTargets(folders, [el({ _id: "f" })]).size).toBe(0);
  });
  it("résiste à une parenté circulaire", () => {
    const loop = [el({ _id: "a", eType: "folder", eParent: "b" }), el({ _id: "b", eType: "folder", eParent: "a" })];
    expect(descendantIds(loop, "a").size).toBe(2);
  });
});

describe("renamedWithExtension", () => {
  const doc = el({ name: "Texte.docx" });
  it("rajoute l'extension retirée", () => expect(renamedWithExtension(doc, "Devoir")).toBe("Devoir.docx"));
  it("ne la double pas", () => expect(renamedWithExtension(doc, " Devoir.DOCX ")).toBe("Devoir.DOCX"));
  it("laisse un dossier tel quel", () => expect(renamedWithExtension(el({ eType: "folder", name: "v1.2" }), "Cours")).toBe("Cours"));
});

describe("downloadUrl", () => {
  it("un document : lui-même", () => expect(downloadUrl([el({ _id: "a" })])).toBe("/workspace/document/a"));
  it("plusieurs éléments ou un dossier : archive", () => {
    expect(downloadUrl([el({ _id: "a" }), el({ _id: "b" })])).toBe("/workspace/document/archive/a,b");
    expect(downloadUrl([el({ _id: "f", eType: "folder" })])).toBe("/workspace/document/archive/f");
  });
});
