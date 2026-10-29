import { odeServices } from "@open-ent/client";
import { NextcloudRaw, SyncDocument, toSyncDocument, visibleChildren } from "../lib/nextcloud";

const params = (name: string, values: string[]) => {
  const sp = new URLSearchParams();
  values.forEach((v) => sp.append(name, v));
  return sp.toString();
};
const root = (userId: string) => `/nextcloud/files/user/${userId}`;
const xsrf = () => decodeURIComponent(document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1] ?? "");

export interface NextcloudQuota {
  used: number;
  total: number;
}

export const nextcloudService = {
  /** Espace utilisé et alloué sur le compte NextCloud (octets). */
  quota: async (userId: string): Promise<NextcloudQuota> => {
    const u = await odeServices.http().get<{ quota?: { used?: number; quota?: number; total?: number } }>(`/nextcloud/user/${userId}`);
    return { used: u?.quota?.used ?? 0, total: u?.quota?.quota ?? u?.quota?.total ?? 0 };
  },

  url: () => odeServices.http().get<{ url: string }>("/nextcloud/config/url").then((r) => r.url),
  urlHidden: () => odeServices.http().get<{ isNextcloudUrlHidden: boolean }>("/nextcloud/config/isNextcloudUrlHidden").then((r) => r.isNextcloudUrlHidden),

  /** Contenu d'un dossier NextCloud (chemin relatif à la racine de l'usager, vide = racine). */
  list: async (userId: string, path: string): Promise<SyncDocument[]> => {
    const res = await odeServices.http().get<{ data: NextcloudRaw[] }>(`${root(userId)}${path ? `?path=${encodeURIComponent(path)}` : ""}`);
    return visibleChildren((res?.data ?? []).map((r) => toSyncDocument(r, userId)), path);
  },
  listTrash: async (userId: string): Promise<SyncDocument[]> => {
    const res = await odeServices.http().get<NextcloudRaw[]>(`${root(userId)}/trash`);
    return (res ?? []).map((r) => toSyncDocument(r, userId));
  },

  /** URL d'édition en ligne (jeton de l'usager, sans connexion NextCloud) pour un document bureautique. */
  editUrl: (userId: string, path: string) =>
    odeServices.http().get<{ url: string }>(`${root(userId)}/edit?path=${encodeURIComponent(`/${path}`)}`).then((r) => r.url),
  /** Crée un document vierge (modèle docx, xlsx ou pptx) dans un dossier NextCloud. */
  createDocument: (userId: string, type: string, name: string, path: string) =>
    odeServices.http().post(`${root(userId)}/create/document?type=${type}&name=${encodeURIComponent(name)}${path ? `&path=${encodeURIComponent(path)}` : ""}`),

  createFolder: (userId: string, path: string) => odeServices.http().post(`${root(userId)}/create/folder?path=${encodeURIComponent(path)}`),
  move: (userId: string, path: string, destPath: string) =>
    odeServices.http().put(`${root(userId)}/move?path=${encodeURIComponent(path)}&destPath=${encodeURIComponent(destPath)}`),
  trash: (userId: string, paths: string[]) => odeServices.http().delete(`${root(userId)}/delete?${params("path", paths)}`),
  restore: (userId: string, paths: string[]) => odeServices.http().put(`${root(userId)}/restore?${params("path", paths)}`),
  deleteForever: (userId: string, paths: string[]) => odeServices.http().delete(`${root(userId)}/trash/delete-documents?${params("path", paths)}`),
  emptyTrash: (userId: string) => odeServices.http().delete(`${root(userId)}/trash/delete`),

  /** NextCloud → espace documentaire (copie ou déplacement) dans le dossier `parentId` (absent = racine). */
  toWorkspace: (userId: string, verb: "copy" | "move", paths: string[], parentId?: string) =>
    odeServices.http().put<Array<{ _id: string; name: string; eType: "file" | "folder"; owner: string }>>(`${root(userId)}/${verb}/workspace?${params("path", paths)}${parentId ? `&parentId=${encodeURIComponent(parentId)}` : ""}`),
  /** Espace documentaire → NextCloud, dans le dossier NextCloud `parentName` (relatif, absent = racine). */
  fromWorkspace: (userId: string, verb: "copy" | "move", ids: string[], parentName?: string) =>
    odeServices.http().put(`${root(userId)}/workspace/${verb}/cloud?${params("id", ids)}${parentName ? `&parentName=${encodeURIComponent(parentName)}` : ""}`),

  /** Envoi de fichiers dans un dossier NextCloud (même appel que l'ancienne interface). */
  upload: (userId: string, files: File[], path: string): Promise<void> =>
    new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const form = new FormData();
      files.forEach((f) => form.append("fileToUpload[]", f));
      xhr.open("PUT", `${root(userId)}/upload${path ? `?path=${encodeURIComponent(path)}` : ""}`);
      xhr.setRequestHeader("X-XSRF-TOKEN", xsrf());
      xhr.setRequestHeader("File-Count", String(files.length));
      xhr.onload = () => (xhr.status >= 200 && xhr.status < 400 ? resolve() : reject(new Error(String(xhr.status))));
      xhr.onerror = () => reject(new Error("network"));
      xhr.send(form);
    }),
};
