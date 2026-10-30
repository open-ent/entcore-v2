import { odeServices } from "@open-ent/client";
import { ImportReport } from "../lib/archive";

const xsrf = () => decodeURIComponent(document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1] ?? "");

/** Erreur d'appel serveur : le code HTTP décide du message (404 : archive introuvable, 413 : trop volumineuse…). */
export class HttpError extends Error {
  constructor(public status: number) {
    super(`HTTP ${status}`);
  }
}

const call = async <T = unknown>(url: string, init: RequestInit = {}): Promise<T> => {
  const res = await fetch(url, { credentials: "same-origin", ...init, headers: { "X-XSRF-TOKEN": xsrf(), ...(init.headers ?? {}) } });
  if (!res.ok) throw new HttpError(res.status);
  const text = await res.text();
  return (text ? JSON.parse(text) : {}) as T;
};

const post = <T>(url: string, body: unknown) => call<T>(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export interface ImportAnalysis {
  quota: number;
  apps: Record<string, { size: number }>;
}

/** Envoi d'une archive : suivi possible (`abort`) tant que le téléversement n'est pas fini. */
export interface UploadHandle {
  promise: Promise<{ importId: string }>;
  abort: () => void;
}

export const archiveService = {
  /** Applications que l'archivage sait exporter (clé = adresse de l'application). */
  exportableApps: async (): Promise<string[]> => Object.keys((await call<{ apps: Record<string, unknown> }>("/archive/conf/public")).apps ?? {}),
  /** Applications de l'usager (registre). */
  myApps: async () => (await odeServices.http().get<{ apps: Array<{ prefix?: string; displayName?: string }> }>("/applications-list"))?.apps ?? [],

  /** Un export est-il déjà en cours ou prêt ? */
  exportExists: async (): Promise<boolean> => !!(await call<{ exists: boolean }>("/archive/export/verify")).exists,
  startExport: (apps: string[]) => post<{ exportId: string }>("/archive/export", { apps }),
  verifyExport: (id: string) => call(`/archive/export/verify/${id}`),
  downloadUrl: (id: string) => `/archive/export/${id}`,

  upload: (file: File, onProgress?: (percent: number) => void): UploadHandle => {
    const xhr = new XMLHttpRequest();
    const form = new FormData();
    form.append("file", file);
    const promise = new Promise<{ importId: string }>((resolve, reject) => {
      xhr.open("POST", "/archive/import/upload");
      xhr.setRequestHeader("X-XSRF-TOKEN", xsrf());
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress?.(Math.round((e.loaded / e.total) * 100));
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            resolve(JSON.parse(xhr.responseText) as { importId: string });
          } catch {
            reject(new HttpError(500));
          }
        } else reject(new HttpError(xhr.status));
      };
      xhr.onerror = () => reject(new HttpError(0));
      xhr.onabort = () => reject(new HttpError(-1));
    });
    xhr.send(form);
    return { promise, abort: () => xhr.abort() };
  },
  analyze: (importId: string) => call<ImportAnalysis>(`/archive/import/analyze/${importId}`),
  cancelImport: (importId: string) => call(`/archive/import/delete/${importId}`).catch(() => undefined),
  launch: (importId: string, apps: Record<string, unknown>) => post<Record<string, ImportReport>>(`/archive/import/${importId}/launch`, { apps }),
};
