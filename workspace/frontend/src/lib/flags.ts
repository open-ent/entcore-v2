/**
 * Drapeaux de configuration du module, posés par `WorkspaceController.view` dans la vue
 * (`<script id="workspace-flags" type="application/json">`) : éditeurs, NextCloud, lecteur externe, cache.
 * Ex. { "enableLool": true, "enableNextcloud": false, "lazyMode": false }
 */
export interface WorkspaceFlags {
  enableLool: boolean;
  enableScratch: boolean;
  enableGeogebra: boolean;
  enableNextcloud: boolean;
  useNextcloudSniplet: boolean;
  enableCloudDrive: boolean;
  cloudDriveGoogleClientId: string;
  cloudDriveGoogleApiKey: string;
  cloudDriveGoogleAppId: string;
  lazyMode: boolean;
  cacheDocTTl: number;
  cacheFolderTtl: number;
  disableFullTextSearch: boolean;
}

export const DEFAULT_FLAGS: WorkspaceFlags = {
  enableLool: false,
  enableScratch: false,
  enableGeogebra: false,
  enableNextcloud: false,
  useNextcloudSniplet: true,
  enableCloudDrive: false,
  cloudDriveGoogleClientId: "",
  cloudDriveGoogleApiKey: "",
  cloudDriveGoogleAppId: "",
  lazyMode: false,
  cacheDocTTl: -1,
  cacheFolderTtl: -1,
  disableFullTextSearch: false,
};

/** Lit les drapeaux ; une vue absente ou illisible donne les valeurs par défaut (tout désactivé), jamais une erreur. */
export const readFlags = (doc: Document = document): WorkspaceFlags => {
  try {
    const raw = doc.getElementById("workspace-flags")?.textContent;
    if (!raw) return DEFAULT_FLAGS;
    return { ...DEFAULT_FLAGS, ...(JSON.parse(raw) as Partial<WorkspaceFlags>) };
  } catch {
    return DEFAULT_FLAGS;
  }
};
