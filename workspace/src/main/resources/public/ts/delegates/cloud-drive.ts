// Intégration « lecteur externe » de l'espace documentaire — Google Drive (étape 1).
//
// Principe retenu : intégration DE CLIENT À CLIENT. Le serveur ENT ne détient ni secret
// OAuth, ni jeton d'accès, ni jeton de rafraîchissement de l'utilisateur. Le consentement
// est demandé par le navigateur à Google (client public OAuth 2.0, sans client_secret),
// le jeton obtenu reste en mémoire JavaScript le temps de l'onglet, et les octets des
// fichiers transitent navigateur -> ENT (import) ou navigateur -> Google (export).
// Conséquence assumée : pas de synchronisation en arrière-plan ni d'accès serveur au
// Drive, et un nouveau consentement à chaque session (Google n'émet pas de jeton de
// rafraîchissement aux clients navigateur).
//
// Le sélecteur Google (Picker) est utilisé volontairement : associé à la portée
// `drive.file`, il laisse l'utilisateur désigner N'IMPORTE QUEL fichier de son Drive et
// n'accorde l'accès qu'aux fichiers ainsi désignés. On évite donc les portées
// « restricted » (drive, drive.readonly) qui imposent une vérification préalable de
// l'application par Google.

import { idiom as lang, notify, currentLanguage } from 'entcore';
import { models, workspaceService, Document } from '../services';

declare let window: any;
declare var CLOUD_DRIVE_GOOGLE_CLIENT_ID: string;
declare var CLOUD_DRIVE_GOOGLE_API_KEY: string;
declare var CLOUD_DRIVE_GOOGLE_APP_ID: string;

const GSI_CLIENT_SRC = 'https://accounts.google.com/gsi/client';
const GAPI_SRC = 'https://apis.google.com/js/api.js';
const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.file';
const DRIVE_FILES_API = 'https://www.googleapis.com/drive/v3/files';
const DRIVE_UPLOAD_API = 'https://www.googleapis.com/upload/drive/v3/files';

// Les documents nativement Google (Docs, Sheets, Slides, Drawings) n'ont pas d'octets
// téléchargeables tels quels : il faut passer par /export en demandant un format cible.
const NATIVE_EXPORT_FORMATS: { [mimeType: string]: { mimeType: string, extension: string } } = {
    'application/vnd.google-apps.document': {
        mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        extension: 'docx'
    },
    'application/vnd.google-apps.spreadsheet': {
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        extension: 'xlsx'
    },
    'application/vnd.google-apps.presentation': {
        mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        extension: 'pptx'
    },
    'application/vnd.google-apps.drawing': { mimeType: 'image/png', extension: 'png' }
};

export interface CloudDriveDelegateScope {
    onInit(cb: () => void);
    safeApply();
    openedFolder: models.FolderContext;
    selectedItems(): Array<models.Element>;
    canDropOnFolder(): boolean;
    isSharedTree(): boolean;
    cloudDrive: {
        busy: boolean;
        provider: string;
    };
    importFromCloudDrive(): Promise<void>;
    exportToCloudDrive(): Promise<void>;
    canUseCloudDrive(): boolean;
    canExportToCloudDrive(): boolean;
}

/** Charge un script tiers une seule fois et résout quand il est prêt. */
const loadedScripts: { [src: string]: Promise<void> } = {};

function loadScript(src: string): Promise<void> {
    if (!loadedScripts[src]) {
        loadedScripts[src] = new Promise<void>((resolve, reject) => {
            const script = document.createElement('script');
            script.src = src;
            script.async = true;
            script.defer = true;
            script.onload = () => resolve();
            script.onerror = () => {
                // Le script n'a pas été chargé : soit la CSP le bloque, soit le poste n'a
                // pas accès aux domaines Google. On efface la mémoïsation pour permettre
                // une nouvelle tentative.
                delete loadedScripts[src];
                reject(new Error(`[cloud-drive] script non chargé : ${src}`));
            };
            document.head.appendChild(script);
        });
    }
    return loadedScripts[src];
}

/** Jeton d'accès Google, en mémoire uniquement — jamais stocké ni envoyé au serveur ENT. */
let accessToken: string = null;
let accessTokenExpiry: number = 0;
let tokenClient: any = null;

function isTokenValid(): boolean {
    // Marge de 60 s pour ne pas partir en requête avec un jeton qui expire en vol.
    return !!accessToken && Date.now() < accessTokenExpiry - 60000;
}

async function requestAccessToken(): Promise<string> {
    if (isTokenValid()) {
        return accessToken;
    }
    await loadScript(GSI_CLIENT_SRC);
    const google = window.google;
    if (!google || !google.accounts || !google.accounts.oauth2) {
        throw new Error('[cloud-drive] bibliothèque Google Identity Services indisponible');
    }
    return new Promise<string>((resolve, reject) => {
        // Le client est recréé à chaque demande : initTokenClient mémorise le callback,
        // en réutiliser un ancien ferait résoudre la mauvaise promesse.
        tokenClient = google.accounts.oauth2.initTokenClient({
            client_id: CLOUD_DRIVE_GOOGLE_CLIENT_ID,
            scope: DRIVE_SCOPE,
            callback: (response: any) => {
                if (response && response.access_token) {
                    accessToken = response.access_token;
                    accessTokenExpiry = Date.now() + (parseInt(response.expires_in, 10) || 3600) * 1000;
                    resolve(accessToken);
                } else {
                    reject(new Error('[cloud-drive] consentement refusé ou jeton absent'));
                }
            },
            error_callback: (err: any) => reject(err)
        });
        // prompt vide : Google ne redemande le consentement que s'il n'a pas déjà été
        // accordé. L'utilisateur qui a déjà autorisé l'ENT dans la journée n'est pas
        // réinterrogé, mais aucun jeton n'est pour autant conservé de notre côté.
        tokenClient.requestAccessToken({ prompt: '' });
    });
}

interface PickedFile {
    id: string;
    name: string;
    mimeType: string;
}

/**
 * Ouvre le sélecteur Google.
 * @param mode 'files' pour choisir des fichiers à importer, 'folder' pour choisir le
 *             dossier de destination d'un export.
 */
async function openPicker(token: string, mode: 'files' | 'folder'): Promise<Array<PickedFile>> {
    await loadScript(GAPI_SRC);
    const gapi = window.gapi;
    await new Promise<void>((resolve, reject) => {
        gapi.load('picker', { callback: () => resolve(), onerror: () => reject(new Error('[cloud-drive] Picker non chargé')) });
    });
    const google = window.google;
    return new Promise<Array<PickedFile>>((resolve) => {
        const view = mode === 'folder'
            ? new google.picker.DocsView(google.picker.ViewId.FOLDERS)
                .setSelectFolderEnabled(true)
                .setIncludeFolders(true)
                .setMimeTypes('application/vnd.google-apps.folder')
            : new google.picker.DocsView(google.picker.ViewId.DOCS)
                .setIncludeFolders(true)
                .setSelectFolderEnabled(false);

        let builder = new google.picker.PickerBuilder()
            .setOAuthToken(token)
            .setAppId(CLOUD_DRIVE_GOOGLE_APP_ID)
            .setOrigin(window.location.protocol + '//' + window.location.host)
            .setLocale(currentLanguage || 'fr')
            .addView(view)
            .setCallback((data: any) => {
                if (data[google.picker.Response.ACTION] === google.picker.Action.PICKED) {
                    resolve((data[google.picker.Response.DOCUMENTS] || []).map((d: any) => ({
                        id: d[google.picker.Document.ID],
                        name: d[google.picker.Document.NAME],
                        mimeType: d[google.picker.Document.MIME_TYPE]
                    })));
                } else if (data[google.picker.Response.ACTION] === google.picker.Action.CANCEL) {
                    resolve([]);
                }
            });
        // La clé d'API (developer key) est publique par nature et restreinte par
        // référent HTTP côté console Google ; elle n'est pas un secret.
        if (CLOUD_DRIVE_GOOGLE_API_KEY) {
            builder = builder.setDeveloperKey(CLOUD_DRIVE_GOOGLE_API_KEY);
        }
        if (mode === 'files') {
            builder = builder.enableFeature(google.picker.Feature.MULTISELECT_ENABLED);
        }
        builder.build().setVisible(true);
    });
}

/** Récupère les octets d'un fichier Drive, en convertissant les formats natifs Google. */
async function downloadFromDrive(file: PickedFile, token: string): Promise<{ blob: Blob, filename: string }> {
    const native = NATIVE_EXPORT_FORMATS[file.mimeType];
    const url = native
        ? `${DRIVE_FILES_API}/${file.id}/export?mimeType=${encodeURIComponent(native.mimeType)}`
        : `${DRIVE_FILES_API}/${file.id}?alt=media`;
    const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
    if (!response.ok) {
        throw new Error(`[cloud-drive] téléchargement impossible (${response.status}) : ${file.name}`);
    }
    const blob = await response.blob();
    let filename = file.name;
    if (native && !filename.toLowerCase().endsWith(`.${native.extension}`)) {
        filename = `${filename}.${native.extension}`;
    }
    return { blob, filename };
}

export function CloudDriveDelegate($scope: CloudDriveDelegateScope) {
    $scope.onInit(() => {
        $scope.cloudDrive = { busy: false, provider: 'google' };

        $scope.canUseCloudDrive = () =>
            !!CLOUD_DRIVE_GOOGLE_CLIENT_ID && $scope.canDropOnFolder() && !$scope.isSharedTree();

        $scope.canExportToCloudDrive = () => {
            if (!CLOUD_DRIVE_GOOGLE_CLIENT_ID) return false;
            const items = $scope.selectedItems();
            return items.length > 0 && items.every(item => item.eType === 'file');
        };

        $scope.importFromCloudDrive = async () => {
            if ($scope.cloudDrive.busy) return;
            try {
                const token = await requestAccessToken();
                const picked = await openPicker(token, 'files');
                if (!picked.length) return;
                $scope.cloudDrive.busy = true;
                $scope.safeApply();
                const parent = $scope.openedFolder ? $scope.openedFolder.folder : null;
                let imported = 0;
                for (const picked_file of picked) {
                    try {
                        const { blob, filename } = await downloadFromDrive(picked_file, token);
                        const doc = new Document();
                        // On repasse par un File pour que Document.fromFile déduise le nom
                        // et l'extension : un Blob nu produirait un document sans nom.
                        const file = new File([blob], filename, { type: blob.type });
                        await workspaceService.createDocument(file, doc, parent);
                        imported++;
                    } catch (err) {
                        console.error(err);
                        notify.error(lang.translate('workspace.cloud.drive.import.error.one') + ' ' + picked_file.name);
                    }
                }
                if (imported > 0) {
                    notify.info(lang.translate('workspace.cloud.drive.import.success'));
                }
            } catch (err) {
                console.error(err);
                notify.error(lang.translate('workspace.cloud.drive.error'));
            } finally {
                $scope.cloudDrive.busy = false;
                $scope.safeApply();
            }
        };

        $scope.exportToCloudDrive = async () => {
            if ($scope.cloudDrive.busy) return;
            const items = $scope.selectedItems().filter(item => item.eType === 'file');
            if (!items.length) return;
            try {
                const token = await requestAccessToken();
                const folders = await openPicker(token, 'folder');
                const parentId = folders.length ? folders[0].id : null;
                $scope.cloudDrive.busy = true;
                $scope.safeApply();
                let exported = 0;
                for (const item of items) {
                    try {
                        const response = await fetch(`/workspace/document/${item._id}`, { credentials: 'same-origin' });
                        if (!response.ok) {
                            throw new Error(`[cloud-drive] lecture du document ENT impossible (${response.status})`);
                        }
                        const blob = await response.blob();
                        // Le workspace range le nom sans son extension (cf. Element.fromFile) :
                        // il faut la recoller, sinon le fichier arrive sans extension dans Drive.
                        const extension = item.metadata ? item.metadata.extension : '';
                        const filename = extension && !item.name.toLowerCase().endsWith(`.${extension.toLowerCase()}`)
                            ? `${item.name}.${extension}`
                            : item.name;
                        // Création en deux temps (métadonnées puis contenu) plutôt qu'un envoi
                        // multipart : l'API Drive attend un corps `multipart/related`, que
                        // FormData ne sait pas produire.
                        const metadata: any = { name: filename };
                        if (parentId) {
                            metadata.parents = [parentId];
                        }
                        const created = await fetch(`${DRIVE_FILES_API}?fields=id`, {
                            method: 'POST',
                            headers: {
                                Authorization: `Bearer ${token}`,
                                'Content-Type': 'application/json'
                            },
                            body: JSON.stringify(metadata)
                        });
                        if (!created.ok) {
                            throw new Error(`[cloud-drive] création du fichier Drive refusée (${created.status})`);
                        }
                        const createdFile = await created.json();
                        const upload = await fetch(`${DRIVE_UPLOAD_API}/${createdFile.id}?uploadType=media`, {
                            method: 'PATCH',
                            headers: {
                                Authorization: `Bearer ${token}`,
                                'Content-Type': blob.type || 'application/octet-stream'
                            },
                            body: blob
                        });
                        if (!upload.ok) {
                            throw new Error(`[cloud-drive] envoi du contenu vers Drive refusé (${upload.status})`);
                        }
                        exported++;
                    } catch (err) {
                        console.error(err);
                        notify.error(lang.translate('workspace.cloud.drive.export.error.one') + ' ' + item.name);
                    }
                }
                if (exported > 0) {
                    notify.info(lang.translate('workspace.cloud.drive.export.success'));
                }
            } catch (err) {
                console.error(err);
                notify.error(lang.translate('workspace.cloud.drive.error'));
            } finally {
                $scope.cloudDrive.busy = false;
                $scope.safeApply();
            }
        };
    });
}
