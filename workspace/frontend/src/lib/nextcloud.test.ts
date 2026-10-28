import { downloadUrl, editInNextcloudUrl, folderPathFor, multiDownloadUrl, nextcloudActions, openInNextcloudUrl, parentPath, pathCrumbs, relativePath, renamedPath, toSyncDocument, visibleChildren } from "./nextcloud";

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
  it("retire le dossier lui-même et les dossiers techniques, dossiers d'abord", () => {
    const docs = [
      toSyncDocument(raw({ path: "/x/u1/Cours/", displayname: "Cours" }), "u1"),
      toSyncDocument(raw({ path: "/x/u1/Cours/b.txt", displayname: "b.txt", isFolder: false }), "u1"),
      toSyncDocument(raw({ path: "/x/u1/Cours/Templates/", displayname: "Templates" }), "u1"),
      toSyncDocument(raw({ path: "/x/u1/Cours/.cache/", displayname: ".cache" }), "u1"),
      toSyncDocument(raw({ path: "/x/u1/Cours/6e/", displayname: "6e" }), "u1"),
    ];
    expect(visibleChildren(docs, "Cours").map((d) => d.name)).toEqual(["6e", "b.txt"]);
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
  it("un fichier : tout, hors modification si le format ne s'y prête pas", () => expect(nextcloudActions([file], false)).toEqual(["download", "open", "rename", "copyToWorkspace", "moveToWorkspace", "trash"]));
  it("un fichier modifiable propose Modifier", () => expect(nextcloudActions([doc({ editable: true })], false)).toContain("edit"));
  it("un dossier : pas de transfert vers l'espace documentaire", () => expect(nextcloudActions([folder], false)).toEqual(["download", "rename", "trash"]));
  it("plusieurs éléments : pas de renommage", () => expect(nextcloudActions([file, file], false)).not.toContain("rename"));
});
