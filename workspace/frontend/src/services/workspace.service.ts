import { odeServices } from "@open-ent/client";
import { Quota, Revision, TreeName, ViewMode, WorkspaceElement } from "../lib/types";

const query = (params: Record<string, string | boolean | undefined>): string => {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined) sp.set(k, String(v));
  return sp.toString();
};

export const workspaceService = {
  /**
   * Contenu d'un emplacement : dossiers ET documents (`includeall`) de l'arbre `tree`, sous `parentId`
   * (absent = racine de l'arbre). Même appel que l'interface AngularJS.
   */
  listContent: (tree: TreeName, parentId?: string) =>
    odeServices
      .http()
      .get<WorkspaceElement[]>(
        `/workspace/documents?${query({ filter: tree, parentId, hierarchical: false, includeall: true })}`,
      ),

  /** Tous les dossiers de l'usager (arborescence de gauche et fil d'Ariane). */
  listFolders: () => odeServices.http().get<WorkspaceElement[]>("/workspace/folders/list?filter=all"),

  getQuota: (userId: string) => odeServices.http().get<Quota>(`/workspace/quota/user/${userId}`),

  /** Préférence d'affichage (« icons » ou « list »), rangée dans la préférence « workspace » de l'usager. */
  async getViewMode(): Promise<ViewMode> {
    const res = await odeServices.http().get<{ preference?: string }>("/userbook/preference/workspace");
    try {
      return JSON.parse(res.preference ?? "{}").view === "list" ? "list" : "icons";
    } catch {
      return "icons";
    }
  },
};

const xsrfToken = (): string => decodeURIComponent(document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1] ?? "");

/** Clé de traduction de l'erreur d'envoi : 413 → fichier trop gros, 403 → pas de droit, sinon le code du serveur. */
export const uploadErrorKey = (status: number, body?: string): string => {
  if (status === 413) return "file.too.large.limit";
  if (status === 403) return "workspace.upload.forbidden";
  try {
    return (JSON.parse(body ?? "{}") as { error?: string }).error || "workspace.react.upload.failed";
  } catch {
    return "workspace.react.upload.failed";
  }
};

/** Crée un dossier sous `parentId` (absent = racine de « Mes documents »). Rejette avec l'erreur du serveur. */
export const createFolder = (name: string, parentId?: string) => {
  // Le serveur lit un formulaire (un corps JSON lui donne une erreur 500).
  const form = new URLSearchParams({ name });
  if (parentId) form.set("parentFolderId", parentId);
  return odeServices.http().post<{ _id: string }>("/workspace/folder", form);
};

/**
 * Envoie un fichier (XMLHttpRequest : seul moyen d'avoir la progression).
 * Rejette avec la clé de traduction de l'erreur.
 */
export const uploadFile = (
  file: File,
  url: string,
  onProgress: (percent: number) => void,
): Promise<void> =>
  new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const form = new FormData();
    form.append("file", file, file.name);
    xhr.open("POST", url);
    xhr.setRequestHeader("X-XSRF-TOKEN", xsrfToken());
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(Math.round((e.loaded / e.total) * 100));
    xhr.onload = () => (xhr.status >= 200 && xhr.status < 400 ? resolve() : reject(uploadErrorKey(xhr.status, xhr.responseText)));
    xhr.onerror = () => reject("workspace.react.upload.failed");
    xhr.send(form);
  });

const ids = (elements: Array<{ _id: string }>) => ({ ids: elements.map((e) => e._id) });

export const elementActions = {
  trash: (els: Array<{ _id: string }>) => odeServices.http().putJson("/workspace/documents/trash", ids(els)),
  restore: (els: Array<{ _id: string }>) => odeServices.http().putJson("/workspace/documents/restore", ids(els)),
  deleteForever: (els: Array<{ _id: string }>) => odeServices.http().deleteJson("/workspace/documents", ids(els)),
  emptyTrash: () => odeServices.http().delete("/workspace/trash"),
  /** Destination absente = racine de « Mes documents ». */
  move: (els: Array<{ _id: string }>, destId?: string) =>
    odeServices.http().putJson(`/workspace/documents/move/${destId ?? "root"}`, ids(els)),
  /** Renvoie les copies créées ; chacune porte `copyFromId` (identifiant de l'original). */
  copy: (els: Array<{ _id: string }>, destId?: string) =>
    odeServices.http().postJson<Array<{ _id: string; name: string; eType: "file" | "folder"; copyFromId?: string; owner: string }>>(`/workspace/documents/copy/${destId ?? "root"}`, ids(els)),
  publish: (id: string) => odeServices.http().put<{ url?: string }>(`/workspace/document/${id}/portal-publish`),
  unpublish: (id: string) => odeServices.http().delete(`/workspace/document/${id}/portal-publish`),
  rename: (el: { _id: string; eType: string }, name: string) =>
    odeServices
      .http()
      .putJson(el.eType === "folder" ? `/workspace/folder/rename/${el._id}` : `/workspace/rename/${el._id}`, { name }),
};

/** Un élément par son identifiant (la visionneuse peut être ouverte par un lien direct, sans passer par la liste). */
export const getElement = async (id: string): Promise<WorkspaceElement | undefined> => {
  const list = await odeServices.http().get<WorkspaceElement[]>(`/workspace/documents?${query({ filter: "all", id })}`);
  return list.find((e) => e._id === id);
};

/** Contenu texte d'un fichier (aperçu texte, CSV, HTML). Rejette si le serveur répond une erreur. */
export const fetchText = async (url: string): Promise<string> => {
  const res = await fetch(url, { credentials: "same-origin" });
  if (!res.ok) throw new Error(String(res.status));
  return res.text();
};

export const revisionService = {
  list: (docId: string) => odeServices.http().get<Revision[]>(`/workspace/document/${docId}/revisions`),
  remove: (docId: string, revisionId: string) => odeServices.http().delete(`/workspace/document/${docId}/revision/${revisionId}`),
  /** Envoie un nouveau fichier comme nouvelle version du document (même appel que l'ancienne interface). */
  add: (docId: string, file: File): Promise<void> =>
    new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const form = new FormData();
      form.append("file", file, file.name);
      xhr.open("PUT", `/workspace/document/${docId}`);
      xhr.setRequestHeader("X-XSRF-TOKEN", xsrfToken());
      xhr.onload = () => (xhr.status >= 200 && xhr.status < 400 ? resolve() : reject(uploadErrorKey(xhr.status, xhr.responseText)));
      xhr.onerror = () => reject("workspace.react.upload.failed");
      xhr.send(form);
    }),
};

export const commentService = {
  /** Commentaire d'un document ou d'un dossier ; renvoie l'identifiant du commentaire créé. */
  add: (el: { _id: string; eType: string }, comment: string) =>
    odeServices
      .http()
      .post<{ id: string }>(el.eType === "folder" ? `/workspace/folder/${el._id}/comment` : `/workspace/document/${el._id}/comment`, new URLSearchParams({ comment })),
  remove: (docId: string, commentId: string) => odeServices.http().delete(`/workspace/document/${docId}/comment/${commentId}`),
};

/** Recherche côté serveur (index texte). Rejette si l'index est absent ou indisponible. */
export const searchElements = (queryString: string) => odeServices.http().get<WorkspaceElement[]>(`/workspace/documents?${queryString}`);
