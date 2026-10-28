// Lecteur externe de l'espace documentaire — Google Drive, « de client à client ».
// Le serveur de l'ENT ne détient ni secret OAuth ni jeton : le consentement est demandé par le navigateur à Google (client public,
// sans secret), le jeton reste en mémoire le temps de l'onglet, et les octets transitent navigateur → ENT (import) ou
// navigateur → Google (export). Le sélecteur Google (Picker) avec la portée `drive.file` n'ouvre que les fichiers désignés.
import { WorkspaceFlags } from "./flags";

const GSI_CLIENT_SRC = "https://accounts.google.com/gsi/client";
const GAPI_SRC = "https://apis.google.com/js/api.js";
const DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
const DRIVE_FILES_API = "https://www.googleapis.com/drive/v3/files";
const DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files";

/** Documents natifs Google : pas d'octets téléchargeables tels quels, on passe par /export vers un format cible. */
export const NATIVE_EXPORT_FORMATS: Record<string, { mimeType: string; extension: string }> = {
  "application/vnd.google-apps.document": { mimeType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document", extension: "docx" },
  "application/vnd.google-apps.spreadsheet": { mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", extension: "xlsx" },
  "application/vnd.google-apps.presentation": { mimeType: "application/vnd.openxmlformats-officedocument.presentationml.presentation", extension: "pptx" },
  "application/vnd.google-apps.drawing": { mimeType: "image/png", extension: "png" },
};

export interface PickedFile {
  id: string;
  name: string;
  mimeType: string;
}

/** Le lecteur n'est proposé que s'il est activé et configuré (identifiant de client OAuth présent). */
export const cloudDriveAvailable = (flags: Pick<WorkspaceFlags, "enableCloudDrive" | "cloudDriveGoogleClientId">): boolean =>
  flags.enableCloudDrive && !!flags.cloudDriveGoogleClientId;

/** Adresse de téléchargement d'un fichier Drive (export pour un document natif Google). */
export const driveDownloadUrl = (file: PickedFile): string => {
  const native = NATIVE_EXPORT_FORMATS[file.mimeType];
  return native ? `${DRIVE_FILES_API}/${file.id}/export?mimeType=${encodeURIComponent(native.mimeType)}` : `${DRIVE_FILES_API}/${file.id}?alt=media`;
};

/** Nom du fichier importé : l'extension du format d'export est ajoutée à un document natif Google. */
export const importedName = (file: PickedFile): string => {
  const native = NATIVE_EXPORT_FORMATS[file.mimeType];
  return native && !file.name.toLowerCase().endsWith(`.${native.extension}`) ? `${file.name}.${native.extension}` : file.name;
};

const loadedScripts: Record<string, Promise<void>> = {};
const loadScript = (src: string): Promise<void> => {
  if (!loadedScripts[src]) {
    loadedScripts[src] = new Promise<void>((resolve, reject) => {
      const script = document.createElement("script");
      script.src = src;
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => {
        // Bloqué par la CSP ou domaines Google inaccessibles : on efface la mémoïsation pour permettre une nouvelle tentative.
        delete loadedScripts[src];
        reject(new Error(`script non chargé : ${src}`));
      };
      document.head.appendChild(script);
    });
  }
  return loadedScripts[src];
};

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Google = any;
const w = () => window as unknown as { google?: Google; gapi?: Google };

let accessToken: string | null = null;
let accessTokenExpiry = 0;
const tokenValid = () => !!accessToken && Date.now() < accessTokenExpiry - 60_000;
/** Pour les tests : oublie le jeton. */
export const resetToken = () => {
  accessToken = null;
  accessTokenExpiry = 0;
};

export const requestAccessToken = async (clientId: string): Promise<string> => {
  if (tokenValid()) return accessToken as string;
  await loadScript(GSI_CLIENT_SRC);
  const google = w().google;
  if (!google?.accounts?.oauth2) throw new Error("bibliothèque Google Identity Services indisponible");
  return new Promise<string>((resolve, reject) => {
    // Le client est recréé à chaque demande : initTokenClient mémorise le callback, en réutiliser un ancien résoudrait la mauvaise promesse.
    const client = google.accounts.oauth2.initTokenClient({
      client_id: clientId,
      scope: DRIVE_SCOPE,
      callback: (response: { access_token?: string; expires_in?: string }) => {
        if (response?.access_token) {
          accessToken = response.access_token;
          accessTokenExpiry = Date.now() + (parseInt(response.expires_in ?? "", 10) || 3600) * 1000;
          resolve(accessToken);
        } else reject(new Error("consentement refusé ou jeton absent"));
      },
      error_callback: (err: unknown) => reject(err),
    });
    client.requestAccessToken({ prompt: "" });
  });
};

/** Sélecteur Google : « files » pour choisir des fichiers à importer, « folder » pour le dossier de destination d'un export. */
export const openPicker = async (token: string, mode: "files" | "folder", flags: Pick<WorkspaceFlags, "cloudDriveGoogleApiKey" | "cloudDriveGoogleAppId">): Promise<PickedFile[]> => {
  await loadScript(GAPI_SRC);
  const gapi = w().gapi;
  await new Promise<void>((resolve, reject) => gapi.load("picker", { callback: () => resolve(), onerror: () => reject(new Error("Picker non chargé")) }));
  const google = w().google;
  return new Promise<PickedFile[]>((resolve) => {
    const view =
      mode === "folder"
        ? new google.picker.DocsView(google.picker.ViewId.FOLDERS).setSelectFolderEnabled(true).setIncludeFolders(true).setMimeTypes("application/vnd.google-apps.folder")
        : new google.picker.DocsView(google.picker.ViewId.DOCS).setIncludeFolders(true).setSelectFolderEnabled(false);
    let builder = new google.picker.PickerBuilder()
      .setOAuthToken(token)
      .setAppId(flags.cloudDriveGoogleAppId)
      .setOrigin(`${window.location.protocol}//${window.location.host}`)
      .setLocale("fr")
      .addView(view)
      .setCallback((data: Record<string, unknown>) => {
        if (data[google.picker.Response.ACTION] === google.picker.Action.PICKED) {
          const docs = (data[google.picker.Response.DOCUMENTS] as Array<Record<string, string>>) ?? [];
          resolve(docs.map((d) => ({ id: d[google.picker.Document.ID], name: d[google.picker.Document.NAME], mimeType: d[google.picker.Document.MIME_TYPE] })));
        } else if (data[google.picker.Response.ACTION] === google.picker.Action.CANCEL) resolve([]);
      });
    // Clé d'API publique par nature, restreinte par référent HTTP côté console Google.
    if (flags.cloudDriveGoogleApiKey) builder = builder.setDeveloperKey(flags.cloudDriveGoogleApiKey);
    if (mode === "files") builder = builder.enableFeature(google.picker.Feature.MULTISELECT_ENABLED);
    builder.build().setVisible(true);
  });
};

/** Récupère les octets d'un fichier Drive. Rejette si Google refuse. */
export const downloadFromDrive = async (file: PickedFile, token: string): Promise<File> => {
  const res = await fetch(driveDownloadUrl(file), { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`téléchargement impossible (${res.status}) : ${file.name}`);
  const blob = await res.blob();
  // Repassé par un File : le nom et l'extension en dépendent pour l'envoi vers l'espace documentaire.
  return new File([blob], importedName(file), { type: blob.type });
};

/** Envoie un document de l'ENT vers Drive (création des métadonnées, puis du contenu), dans le dossier `parentId` s'il est fourni. */
export const uploadToDrive = async (docId: string, name: string, token: string, parentId?: string): Promise<void> => {
  const source = await fetch(`/workspace/document/${docId}`, { credentials: "same-origin" });
  if (!source.ok) throw new Error(`lecture du document impossible (${source.status})`);
  const blob = await source.blob();
  const metadata: { name: string; parents?: string[] } = { name };
  if (parentId) metadata.parents = [parentId];
  const created = await fetch(`${DRIVE_FILES_API}?fields=id`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify(metadata),
  });
  if (!created.ok) throw new Error(`création du fichier Drive refusée (${created.status})`);
  const { id } = (await created.json()) as { id: string };
  const upload = await fetch(`${DRIVE_UPLOAD_API}/${id}?uploadType=media`, {
    method: "PATCH",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": blob.type || "application/octet-stream" },
    body: blob,
  });
  if (!upload.ok) throw new Error(`envoi du contenu vers Drive refusé (${upload.status})`);
};
