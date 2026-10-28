import { odeServices } from "@open-ent/client";
import { Quota, TreeName, ViewMode, WorkspaceElement } from "../lib/types";

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

/** Crée un dossier sous `parentId` (absent = racine de « Mes documents »). Rejette avec le code d'erreur du serveur (ex. doublon). */
export const createFolder = (name: string, parentId?: string) =>
  odeServices.http().postJson<{ _id: string }>("/workspace/folder", { name, parentFolderId: parentId ?? null });

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
