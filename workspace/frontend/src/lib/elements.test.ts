import { breadcrumb, childFolders, fileKind, formatSize, isTreeName, parseDate, sortElements } from "./elements";
import { WorkspaceElement } from "./types";

const el = (p: Partial<WorkspaceElement>): WorkspaceElement => ({
  _id: p._id ?? "x",
  name: "n",
  eType: "file",
  eParent: null,
  owner: "u",
  ...p,
});

describe("fileKind", () => {
  it.each([
    ["image/png", "image"],
    ["application/pdf", "pdf"],
    ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "sheet"],
    ["application/vnd.openxmlformats-officedocument.presentationml.presentation", "slides"],
    ["application/vnd.openxmlformats-officedocument.wordprocessingml.document", "doc"],
    ["text/plain", "text"],
    ["application/zip", "archive"],
    [undefined, "other"],
  ])("%s → %s", (mime, kind) => expect(fileKind(mime as string | undefined)).toBe(kind));
});

describe("formatSize", () => {
  it("convertit en unités lisibles", () => {
    expect(formatSize(512)).toBe("512 o");
    expect(formatSize(1163)).toBe("1,1 Ko");
    expect(formatSize(52428800)).toBe("50,0 Mo");
  });
  it("renvoie une chaîne vide sans taille", () => {
    expect(formatSize(undefined)).toBe("");
    expect(formatSize(-1)).toBe("");
  });
});

describe("parseDate", () => {
  it("lit le format du serveur (point entre minutes et secondes)", () => {
    const d = parseDate("2026-10-28 17:22.33.755");
    expect(d?.getFullYear()).toBe(2026);
    expect(d?.getMinutes()).toBe(22);
    expect(d?.getSeconds()).toBe(33);
  });
  it("ignore une date illisible", () => {
    expect(parseDate("n'importe quoi")).toBeUndefined();
    expect(parseDate(undefined)).toBeUndefined();
  });
});

describe("sortElements", () => {
  const list = [
    el({ _id: "1", name: "zèbre.txt" }),
    el({ _id: "2", name: "Zoo", eType: "folder" }),
    el({ _id: "3", name: "abeille.txt" }),
    el({ _id: "4", name: "Archives", eType: "folder" }),
  ];
  it("place les dossiers d'abord, puis l'ordre alphabétique sans tenir compte de la casse", () => {
    expect(sortElements(list).map((e) => e._id)).toEqual(["4", "2", "3", "1"]);
  });
  it("trie par date de modification décroissante", () => {
    const docs = [
      el({ _id: "a", modified: "2026-10-01 10:00.00.000" }),
      el({ _id: "b", modified: "2026-10-03 10:00.00.000" }),
    ];
    expect(sortElements(docs, "modified").map((e) => e._id)).toEqual(["b", "a"]);
  });
  it("trie par taille décroissante", () => {
    const docs = [el({ _id: "a", metadata: { size: 10 } }), el({ _id: "b", metadata: { size: 99 } })];
    expect(sortElements(docs, "size").map((e) => e._id)).toEqual(["b", "a"]);
  });
});

describe("dossiers", () => {
  const folders = [
    el({ _id: "c", name: "Cours", eType: "folder", eParent: null }),
    el({ _id: "s", name: "6e", eType: "folder", eParent: "c" }),
    el({ _id: "t", name: "5e", eType: "folder", eParent: "c" }),
  ];
  it("liste les dossiers enfants triés", () => {
    expect(childFolders(folders, "c").map((f) => f.name)).toEqual(["5e", "6e"]);
    expect(childFolders(folders, null).map((f) => f.name)).toEqual(["Cours"]);
  });
  it("n'affiche pas un dossier mis à la corbeille", () => {
    const avecCorbeille = [...folders, el({ _id: "x", name: "Supprimé", eType: "folder", eParent: null, trasher: "u" })];
    expect(childFolders(avecCorbeille, null).map((f) => f.name)).toEqual(["Cours"]);
  });
  it("construit le chemin de la racine au dossier courant", () => {
    expect(breadcrumb(folders, "s").map((f) => f.name)).toEqual(["Cours", "6e"]);
    expect(breadcrumb(folders, null)).toEqual([]);
  });
  it("résiste à une parenté circulaire ou à un dossier inconnu", () => {
    const loop = [el({ _id: "a", eType: "folder", eParent: "b" }), el({ _id: "b", eType: "folder", eParent: "a" })];
    expect(breadcrumb(loop, "a")).toHaveLength(2);
    expect(breadcrumb(folders, "inconnu")).toEqual([]);
  });
});

describe("isTreeName", () => {
  it("reconnaît les arbres racines", () => {
    expect(isTreeName("owner")).toBe(true);
    expect(isTreeName("trash")).toBe(true);
    expect(isTreeName("autre")).toBe(false);
    expect(isTreeName(undefined)).toBe(false);
  });
});
