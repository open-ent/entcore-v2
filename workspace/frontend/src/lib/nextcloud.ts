import { fileKind, FileKind, parseDate, SortKey } from "./elements";

export const NEXTCLOUD_VIEW = "fr.openent.nextcloud.controller.NextcloudController|view";

/** Réponse de `GET /nextcloud/files/user/:id` : un document ou un dossier du compte NextCloud. */
export interface NextcloudRaw {
  path: string;
  displayname: string;
  ownerDisplayName?: string;
  contentType?: string;
  size?: number;
  fileId?: number;
  isFolder: boolean;
  lastModified?: string;
}

export interface SyncDocument {
  /** Chemin relatif à la racine de l'usager, sans « / » de tête ni de queue : "Cours/6e" (vide = racine). */
  path: string;
  name: string;
  isFolder: boolean;
  contentType?: string;
  size?: number;
  fileId?: number;
  lastModified?: string;
  /** Propriétaire affiché (nom renvoyé par NextCloud). */
  ownerName?: string;
  kind: FileKind | "folder";
  /** Document bureautique modifiable en ligne (texte, tableur, présentation) ; un PDF s'affiche dans le navigateur, il ne s'édite pas. */
  editable: boolean;
}

const trimSlashes = (p: string) => p.replace(/^\/+/, "").replace(/\/+$/, "");
const safeDecode = (s: string) => {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
};

/**
 * Le serveur renvoie le chemin WebDAV complet (/remote.php/dav/files/<identifiant>/Cours/6e/) : on ne garde que la partie
 * qui suit l'identifiant de l'usager. Ex. ("/remote.php/dav/files/u1/Cours/6e/", "u1") → "Cours/6e".
 */
export const relativePath = (href: string, userId: string): string => {
  const p = safeDecode(href ?? "");
  const i = p.indexOf(`/${userId}`);
  return trimSlashes(i >= 0 ? p.substring(i + userId.length + 1) : p);
};

/** Convertit un élément de la réponse du serveur. Le nom est « décodé » (les espaces arrivent en %20). */
export const toSyncDocument = (raw: NextcloudRaw, userId: string): SyncDocument => {
  const kind = raw.isFolder ? "folder" : fileKind(raw.contentType);
  return {
    path: relativePath(raw.path, userId),
    name: safeDecode(raw.displayname),
    isFolder: raw.isFolder,
    contentType: raw.contentType,
    size: raw.size,
    fileId: raw.fileId,
    lastModified: raw.lastModified,
    ownerName: raw.ownerDisplayName,
    kind,
    editable: !raw.isFolder && ["doc", "sheet", "slides"].includes(kind),
  };
};

/** Dossiers techniques du serveur sans intérêt comme DESTINATION d'un envoi (gabarits, synchronisation d'établissement, fichiers cachés) : écartés du seul sélecteur de dossier. */
export const HIDDEN_FOLDER = /^(Templates|ENT_PARTAGE_UAI_.*|\..*)$/i;

/** Contenu d'un dossier : sans le dossier lui-même (renvoyé en tête par le serveur) ; dossiers d'abord. */
export const visibleChildren = (docs: SyncDocument[], currentPath: string): SyncDocument[] =>
  docs
    .filter((d) => d.path !== trimSlashes(currentPath))
    .sort((a, b) => (a.isFolder === b.isFolder ? a.name.localeCompare(b.name, "fr", { sensitivity: "base", numeric: true }) : a.isFolder ? -1 : 1));

/** Chemin du dossier parent : "Cours/6e" → "Cours", "Cours" → "" (racine). */
export const parentPath = (path: string): string => {
  const p = trimSlashes(path);
  return p.includes("/") ? p.substring(0, p.lastIndexOf("/")) : "";
};

/** Fil d'Ariane : "Cours/6e" → [{name:"Cours", path:"Cours"}, {name:"6e", path:"Cours/6e"}]. */
export const pathCrumbs = (path: string): Array<{ name: string; path: string }> => {
  const parts = trimSlashes(path).split("/").filter(Boolean);
  return parts.map((name, i) => ({ name, path: parts.slice(0, i + 1).join("/") }));
};

/** Téléchargement d'un fichier ou d'un dossier (archive). */
export const downloadUrl = (userId: string, d: Pick<SyncDocument, "name" | "path" | "contentType" | "isFolder">, inline = false): string => {
  // & # + % dans un nom couperaient ou fausseraient le paramètre : on les encode, les « / » restent lisibles
  const pathParam = d.path ? `?path=${encodeURIComponent(d.path).replace(/%2F/g, "/").replace(/%20/g, " ")}` : "";
  const type = d.path && d.contentType ? `&contentType=${d.contentType}` : "";
  const folder = pathParam ? `&isFolder=${d.isFolder}` : `?isFolder=${d.isFolder}`;
  return `/nextcloud/files/user/${userId}/file/${encodeURIComponent(d.name)}/download${pathParam}${type}${folder}${inline ? "&inline=true" : ""}`;
};

/** Types de documents vierges que le serveur sait créer à partir d'un modèle. */
export const NEW_DOCUMENT_TYPES = ["docx", "xlsx", "pptx"] as const;
export type NewDocumentType = (typeof NEW_DOCUMENT_TYPES)[number];

/** Nom saisi pour un document vierge : on n'ajoute pas l'extension, le serveur s'en charge. Vide ou fait d'espaces → refusé. */
export const newDocumentNameError = (raw: string): "empty" | null => (raw.trim() ? null : "empty");

/** Téléchargement groupé (archive) de plusieurs éléments d'un même dossier. */
export const multiDownloadUrl = (userId: string, parent: string, names: string[]): string =>
  `/nextcloud/files/user/${userId}/multiple/download?path=${parent}${names.map((n) => `&file=${n}`).join("")}`;

/** Ouvrir un fichier dans l'interface NextCloud (dossier contenant + identifiant du fichier). */
export const openInNextcloudUrl = (baseUrl: string, d: Pick<SyncDocument, "path" | "fileId">): string => {
  const dir = d.path.includes("/") ? `/${d.path.substring(0, d.path.lastIndexOf("/"))}` : "/";
  return `${baseUrl}/index.php/apps/files?dir=${dir}&openfile=${d.fileId}`;
};

export const editInNextcloudUrl = (baseUrl: string, d: Pick<SyncDocument, "fileId">): string => `${baseUrl}/index.php/apps/onlyoffice/${d.fileId}`;

export type NextcloudActionId = "download" | "edit" | "rename" | "share" | "trash" | "copyToWorkspace" | "moveToWorkspace" | "restore" | "deleteForever";

/**
 * Actions de la sélection dans NextCloud :
 *  - corbeille : restaurer, supprimer définitivement ;
 *  - sinon : télécharger, déplacer ou copier vers l'espace documentaire (fichiers seulement), mettre à la corbeille, et pour un
 *    seul élément : renommer et, si le format le permet, éditer (éditeur en ligne de NextCloud).
 */
export const nextcloudActions = (selection: SyncDocument[], inTrash: boolean): NextcloudActionId[] => {
  if (selection.length === 0) return [];
  if (inTrash) return ["restore", "deleteForever"];
  const single = selection.length === 1;
  const onlyFiles = selection.every((d) => !d.isFolder);
  const actions: NextcloudActionId[] = ["download"];
  if (single && selection[0].editable) actions.push("edit");
  if (single) actions.push("rename");
  // partager un document NextCloud : un exemplaire est placé dans l'espace documentaire, où se règle le partage
  if (onlyFiles) actions.push("share", "copyToWorkspace", "moveToWorkspace");
  actions.push("trash");
  return actions;
};

/** Nouveau chemin d'un élément renommé : "Cours/a.txt" + "b.txt" → "Cours/b.txt". */
export const renamedPath = (d: Pick<SyncDocument, "path">, newName: string): string => {
  const parent = parentPath(d.path);
  return parent ? `${parent}/${newName}` : newName;
};

export const folderPathFor = (current: string, name: string): string => (trimSlashes(current) ? `${trimSlashes(current)}/${name}` : name);

/** Date de dernière modification d'un élément NextCloud (« 2026-08-25T10:00:00Z » ou « 2026-08-25 10:00:00 »), en millisecondes ; 0 si absente. */
export const modifiedTime = (d: Pick<SyncDocument, "lastModified">): number => {
  const raw = d.lastModified ?? "";
  const iso = Date.parse(raw);
  if (!Number.isNaN(iso)) return iso;
  return parseDate(raw.replace("T", " ").replace(/\.\d+Z?$/, "").replace(/(\d\d:\d\d):(\d\d)/, "$1.$2"))?.getTime() ?? 0;
};

/**
 * Tri des éléments NextCloud comme celui des documents personnels : dossiers d'abord, puis le critère (titre, propriétaire,
 * dernière modification, poids), dans le sens demandé. Sans `desc` : titre et propriétaire croissants, date et poids décroissants.
 */
export const sortSyncDocuments = (docs: SyncDocument[], key: SortKey = "name", desc?: boolean): SyncDocument[] => {
  const down = desc ?? (key === "modified" || key === "size");
  const text = (a?: string, b?: string) => (a ?? "").localeCompare(b ?? "", "fr", { sensitivity: "base", numeric: true });
  const ascending = (a: SyncDocument, b: SyncDocument): number => {
    if (key === "modified") return modifiedTime(a) - modifiedTime(b);
    if (key === "size") return (a.size ?? 0) - (b.size ?? 0);
    if (key === "owner") return text(a.ownerName, b.ownerName) || text(a.name, b.name);
    return text(a.name, b.name);
  };
  return [...docs].sort((a, b) => (a.isFolder !== b.isFolder ? (a.isFolder ? -1 : 1) : down ? -ascending(a, b) : ascending(a, b)));
};

/** Type de données du glisser-déposer interne de la page NextCloud (chemins déplacés vers un dossier NextCloud). */
export const NC_DRAG_TYPE = "application/x-nextcloud-paths";

/**
 * Déplacements à faire quand on lâche ces éléments sur un dossier NextCloud : (chemin actuel → nouveau chemin).
 * Sont écartés : l'élément lâché sur lui-même ou sur l'un de ses sous-dossiers, et ceux qui y sont déjà.
 * Ex. ["a.txt", "Cours"] lâchés sur « Archives » → [a.txt → Archives/a.txt, Cours → Archives/Cours] ;
 *     « Cours » lâché sur « Cours/6e » → rien (dans lui-même).
 */
export const dropMoves = (paths: string[], destFolder: string): Array<{ from: string; to: string }> => {
  const dest = destFolder.replace(/^\/+|\/+$/g, "");
  return paths
    .filter((p) => dest !== p && !dest.startsWith(`${p}/`) && parentPath(p) !== dest)
    .map((p) => ({ from: p, to: dest ? `${dest}/${p.substring(p.lastIndexOf("/") + 1)}` : p.substring(p.lastIndexOf("/") + 1) }));
};
