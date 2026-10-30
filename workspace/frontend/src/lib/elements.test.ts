import { breadcrumb, childFolders, fileKind, formatSize, isTreeName, parseDate, sortElements, foldersOfTree, rootFolders } from "./elements";
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

describe("sortElements — sens et propriétaire", () => {
  const f = (id: string, name: string, ownerName: string, size: number, modified: string) =>
    ({ _id: id, name, eType: "file", eParent: null, owner: "u", ownerName, modified, metadata: { size } }) as never;
  const docs = [f("a", "b.txt", "Zoé", 10, "2026-01-02 10:00.00.000"), f("b", "a.txt", "Alice", 30, "2026-01-01 10:00.00.000"), f("c", "c.txt", "Marc", 20, "2026-01-03 10:00.00.000")];
  const ids = (list: ReturnType<typeof sortElements>) => list.map((e) => e._id);
  it("trie sur le propriétaire, croissant puis décroissant", () => {
    expect(ids(sortElements(docs, "owner", false))).toEqual(["b", "c", "a"]);
    expect(ids(sortElements(docs, "owner", true))).toEqual(["a", "c", "b"]);
  });
  it("le sens demandé l'emporte sur le sens habituel", () => {
    expect(ids(sortElements(docs, "size", false))).toEqual(["a", "c", "b"]);
    expect(ids(sortElements(docs, "size", true))).toEqual(["b", "c", "a"]);
    expect(ids(sortElements(docs, "name", true))).toEqual(["c", "a", "b"]);
  });
  it("les dossiers restent en tête quel que soit le sens", () => {
    const withFolder = [...docs, { _id: "d", name: "Zdossier", eType: "folder", eParent: null, owner: "u" } as never];
    expect(sortElements(withFolder, "name", true)[0]._id).toBe("d");
    expect(sortElements(withFolder, "size", false)[0]._id).toBe("d");
  });
});

describe("foldersOfTree / rootFolders", () => {
  const f = (_id: string, p: Partial<WorkspaceElement> = {}): WorkspaceElement => ({ _id, name: _id, eType: "folder", eParent: null, owner: "me", ...p }) as WorkspaceElement;
  const folders = [
    f("cours"), // à moi, non partagé
    f("eleve", { eParent: "cours" }),
    f("equipe", { owner: "autre", shared: [{ userId: "me" } as never] }), // partagé avec moi, parent invisible
    f("equipe-sous", { owner: "autre", eParent: "equipe", inheritedShares: [{ userId: "me" } as never] }),
    f("mon-partage", { shared: [{ userId: "x" } as never] }), // à moi mais partagé
  ];

  it("« Documents personnels » : mes dossiers non partagés ; « Documents partagés » : tous les dossiers partagés", () => {
    expect(foldersOfTree("owner", folders, "me").map((x) => x._id)).toEqual(["cours", "eleve"]);
    expect(foldersOfTree("shared", folders, "me").map((x) => x._id)).toEqual(["equipe", "equipe-sous", "mon-partage"]);
  });

  it("sans identifiant d'usager : tous les dossiers", () => {
    expect(foldersOfTree("shared", folders)).toHaveLength(5);
  });

  it("premier niveau : sans parent, ou dont le parent n'est pas dans l'ensemble (dossier partagé directement)", () => {
    expect(rootFolders(foldersOfTree("shared", folders, "me")).map((x) => x._id).sort()).toEqual(["equipe", "mon-partage"]);
    expect(rootFolders(foldersOfTree("owner", folders, "me")).map((x) => x._id)).toEqual(["cours"]);
  });
});
