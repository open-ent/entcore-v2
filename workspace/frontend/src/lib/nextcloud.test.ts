import { dropMoves, sortSyncDocuments, SyncDocument, NEW_DOCUMENT_TYPES, newDocumentNameError, HIDDEN_FOLDER, downloadUrl, editInNextcloudUrl, folderPathFor, multiDownloadUrl, nextcloudActions, openInNextcloudUrl, parentPath, pathCrumbs, relativePath, renamedPath, toSyncDocument, visibleChildren } from "./nextcloud";

const raw = (p: Partial<Parameters<typeof toSyncDocument>[0]>) => ({ path: "/remote.php/dav/files/u1/Cours/", displayname: "Cours", isFolder: true, ...p });
const doc = (p: Partial<ReturnType<typeof toSyncDocument>>) => ({ ...toSyncDocument(raw({ path: "/remote.php/dav/files/u1/a.txt", displayname: "a.txt", isFolder: false, contentType: "text/plain" }), "u1"), ...p });

describe("relativePath / toSyncDocument", () => {
  it("garde la partie qui suit l'identifiant de l'usager", () => {
    expect(relativePath("/remote.php/dav/files/u1/Cours/6e/", "u1")).toBe("Cours/6e");
    expect(relativePath("/remote.php/dav/files/u1/", "u1")).toBe("");
  });
  it("décode les noms (%20) et convertit le type", () => {
    const d = toSyncDocument(raw({ path: "/remote.php/dav/files/u1/Mon%20devoir.docx", displayname: "Mon%20devoir.docx", isFolder: false, contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document" }), "u1");
    expect(d.name).toBe("Mon devoir.docx");
    expect(d.path).toBe("Mon devoir.docx");
    expect(d.kind).toBe("doc");
    expect(d.editable).toBe(true);
  });
  it("un dossier n'est pas modifiable", () => expect(toSyncDocument(raw({}), "u1")).toMatchObject({ kind: "folder", editable: false }));
});

describe("visibleChildren", () => {
  it("retire le dossier lui-même seulement, dossiers d'abord ; les dossiers techniques restent visibles", () => {
    const docs = [
      toSyncDocument(raw({ path: "/x/u1/Cours/", displayname: "Cours" }), "u1"),
      toSyncDocument(raw({ path: "/x/u1/Cours/b.txt", displayname: "b.txt", isFolder: false }), "u1"),
      toSyncDocument(raw({ path: "/x/u1/Cours/Templates/", displayname: "Templates" }), "u1"),
      toSyncDocument(raw({ path: "/x/u1/Cours/.cache/", displayname: ".cache" }), "u1"),
      toSyncDocument(raw({ path: "/x/u1/Cours/6e/", displayname: "6e" }), "u1"),
    ];
    expect(visibleChildren(docs, "Cours").map((d) => d.name)).toEqual([".cache", "6e", "Templates", "b.txt"]);
  });
});

describe("dossiers techniques", () => {
  it("sont reconnus pour le sélecteur de destination", () => {
    for (const n of ["Templates", "ENT_PARTAGE_UAI_0291104T", ".cache"]) expect(HIDDEN_FOLDER.test(n)).toBe(true);
    expect(HIDDEN_FOLDER.test("Cours")).toBe(false);
  });
});

describe("chemins", () => {
  it("parent et fil d'Ariane", () => {
    expect(parentPath("Cours/6e")).toBe("Cours");
    expect(parentPath("Cours")).toBe("");
    expect(pathCrumbs("Cours/6e")).toEqual([{ name: "Cours", path: "Cours" }, { name: "6e", path: "Cours/6e" }]);
    expect(pathCrumbs("")).toEqual([]);
  });
  it("renommage et nouveau dossier", () => {
    expect(renamedPath({ path: "Cours/a.txt" }, "b.txt")).toBe("Cours/b.txt");
    expect(renamedPath({ path: "a.txt" }, "b.txt")).toBe("b.txt");
    expect(folderPathFor("Cours", "6e")).toBe("Cours/6e");
    expect(folderPathFor("", "6e")).toBe("6e");
  });
});

describe("adresses", () => {
  it("téléchargement d'un fichier et groupé", () => {
    expect(downloadUrl("u1", { name: "a b.txt", path: "Cours/a b.txt", contentType: "text/plain", isFolder: false })).toBe("/nextcloud/files/user/u1/file/a%20b.txt/download?path=Cours/a b.txt&contentType=text/plain&isFolder=false");
    expect(multiDownloadUrl("u1", "Cours", ["a", "b"])).toBe("/nextcloud/files/user/u1/multiple/download?path=Cours&file=a&file=b");
  });
  it("un nom comportant & # + ne fausse pas le chemin", () => {
    expect(downloadUrl("u1", { name: "a&b.pdf", path: "Cours/a&b #1+2.pdf", contentType: "application/pdf", isFolder: false })).toContain("?path=Cours/a%26b %231%2B2.pdf&");
    expect(downloadUrl("u1", { name: "a&b #1.pdf", path: "a&b #1.pdf", contentType: "application/pdf", isFolder: false })).toContain("/file/a%26b%20%231.pdf/download");
  });
  it("un PDF s'affiche dans le navigateur, il n'est pas proposé à l'édition", () => {
    const d = toSyncDocument({ path: "/remote.php/dav/files/u1/a.pdf", displayname: "a.pdf", isFolder: false, contentType: "application/pdf", size: 1, fileId: "1", lastModified: "" } as never, "u1");
    expect(d.editable).toBe(false);
  });
  it("ouverture dans le navigateur : l'option inline s'ajoute", () => {
    expect(downloadUrl("u1", { name: "a.pdf", path: "a.pdf", contentType: "application/pdf", isFolder: false }, true)).toContain("&inline=true");
  });
  it("ouvrir et modifier dans NextCloud", () => {
    expect(openInNextcloudUrl("https://nc", { path: "Cours/a.txt", fileId: 7 })).toBe("https://nc/index.php/apps/files?dir=/Cours&openfile=7");
    expect(openInNextcloudUrl("https://nc", { path: "a.txt", fileId: 7 })).toBe("https://nc/index.php/apps/files?dir=/&openfile=7");
    expect(editInNextcloudUrl("https://nc", { fileId: 7 })).toBe("https://nc/index.php/apps/onlyoffice/7");
  });
});

describe("nextcloudActions", () => {
  const file = doc({});
  const folder = doc({ isFolder: true, kind: "folder", editable: false });
  it("sans sélection, rien", () => expect(nextcloudActions([], false)).toEqual([]));
  it("corbeille : restaurer et supprimer définitivement", () => expect(nextcloudActions([file], true)).toEqual(["restore", "deleteForever"]));
  it("un fichier : tout, hors modification si le format ne s'y prête pas", () => expect(nextcloudActions([file], false)).toEqual(["download", "rename", "share", "copyToWorkspace", "moveToWorkspace", "trash"]));
  it("un fichier modifiable propose Éditer", () => expect(nextcloudActions([doc({ editable: true })], false)).toContain("edit"));
  it("un dossier : pas de transfert vers l'espace documentaire", () => expect(nextcloudActions([folder], false)).toEqual(["download", "rename", "trash"]));
  it("plusieurs fichiers : partage possible (un exemplaire de chacun va dans l'espace documentaire)", () => expect(nextcloudActions([file, file], false)).toContain("share"));
  it("un dossier ou la corbeille : pas de partage", () => {
    expect(nextcloudActions([folder], false)).not.toContain("share");
    expect(nextcloudActions([file], true)).not.toContain("share");
  });
  it("plusieurs éléments : pas de renommage", () => expect(nextcloudActions([file, file], false)).not.toContain("rename"));
});

describe("document vierge", () => {
  it("types proposés et nom obligatoire", () => {
    expect([...NEW_DOCUMENT_TYPES]).toEqual(["docx", "xlsx", "pptx"]);
    expect(newDocumentNameError("  ")).toBe("empty");
    expect(newDocumentNameError("Devoir")).toBeNull();
  });
});

describe("sortSyncDocuments", () => {
  const f = (name: string, ownerName: string, size: number, lastModified: string, isFolder = false): SyncDocument =>
    ({ path: name, name, isFolder, kind: isFolder ? "folder" : "text", editable: false, ownerName, size, lastModified }) as SyncDocument;
  const docs = [f("b.txt", "Zoé", 10, "2026-08-02T10:00:00Z"), f("a.txt", "Alice", 30, "2026-08-01T10:00:00Z"), f("c.txt", "Marc", 20, "2026-08-03T10:00:00Z"), f("Zdossier", "Bob", 0, "2026-07-01T10:00:00Z", true)];
  const names = (l: SyncDocument[]) => l.map((d) => d.name);
  it("titre, croissant puis décroissant ; les dossiers restent en tête", () => {
    expect(names(sortSyncDocuments(docs, "name", false))).toEqual(["Zdossier", "a.txt", "b.txt", "c.txt"]);
    expect(names(sortSyncDocuments(docs, "name", true))).toEqual(["Zdossier", "c.txt", "b.txt", "a.txt"]);
  });
  it("propriétaire", () => {
    expect(names(sortSyncDocuments(docs, "owner", false))).toEqual(["Zdossier", "a.txt", "c.txt", "b.txt"]);
  });
  it("dernière modification : la plus récente d'abord par défaut", () => {
    expect(names(sortSyncDocuments(docs, "modified"))).toEqual(["Zdossier", "c.txt", "b.txt", "a.txt"]);
    expect(names(sortSyncDocuments(docs, "modified", false))).toEqual(["Zdossier", "a.txt", "b.txt", "c.txt"]);
  });
  it("poids : le plus lourd d'abord par défaut", () => {
    expect(names(sortSyncDocuments(docs, "size"))).toEqual(["Zdossier", "a.txt", "c.txt", "b.txt"]);
    expect(names(sortSyncDocuments(docs, "size", false))).toEqual(["Zdossier", "b.txt", "c.txt", "a.txt"]);
  });
  it("lit le propriétaire renvoyé par NextCloud", () => {
    expect(toSyncDocument({ path: "/remote.php/dav/files/u1/a.txt", displayname: "a.txt", isFolder: false, ownerDisplayName: "Alice" }, "u1").ownerName).toBe("Alice");
  });
});

describe("dropMoves", () => {
  it("déplace chaque élément dans le dossier visé", () => {
    expect(dropMoves(["a.txt", "Cours"], "Archives")).toEqual([
      { from: "a.txt", to: "Archives/a.txt" },
      { from: "Cours", to: "Archives/Cours" },
    ]);
    expect(dropMoves(["Cours/a.txt"], "Archives/2026")).toEqual([{ from: "Cours/a.txt", to: "Archives/2026/a.txt" }]);
  });
  it("jamais un dossier dans lui-même ni dans un de ses sous-dossiers, ni un élément déjà à sa place", () => {
    expect(dropMoves(["Cours"], "Cours")).toEqual([]);
    expect(dropMoves(["Cours"], "Cours/6e")).toEqual([]);
    expect(dropMoves(["Cours/a.txt"], "Cours")).toEqual([]);
  });
  it("vers la racine", () => {
    expect(dropMoves(["Cours/a.txt"], "")).toEqual([{ from: "Cours/a.txt", to: "a.txt" }]);
  });
});
