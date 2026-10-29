import { TreeName, WorkspaceElement } from "./types";

export type ActionId = "rename" | "copy" | "move" | "trash" | "restore" | "delete" | "download" | "share" | "publish" | "history" | "comments" | "location" | "openLool" | "openScratch" | "openGeogebra" | "ncCopy" | "ncMove" | "driveExport";

/** Ce qui conditionne certaines actions : droit de gestion sur la sélection, profil habilité à publier au portail. */
export interface ActionContext {
  /** Vrai si l'usager gère (partage) l'élément sélectionné — toujours vrai dans « Mes documents ». */
  canManage?: boolean;
  /** Vrai si l'usager gère TOUS les éléments sélectionnés (propriétaire ou droit de gestion) : conditionne renommer, déplacer, corbeille. */
  managerOnAll?: boolean;
  /** Administrateur local ou super-administrateur : peut publier un document sur le portail public. */
  canPublish?: boolean;
  /** Résultats de recherche affichés : propose d'ouvrir l'emplacement d'un résultat. */
  searching?: boolean;
  /** Intégrations disponibles pour le document sélectionné (éditeur en ligne, Scratch, GeoGebra). */
  integration?: { lool?: boolean; scratch?: boolean; geogebra?: boolean };
  /** NextCloud activé et accessible à l'usager : propose d'y envoyer des documents. */
  nextcloud?: boolean;
  /** Lecteur externe Google Drive activé et configuré : propose d'y envoyer des documents. */
  cloudDrive?: boolean;
}

/**
 * Actions proposées pour la sélection, selon l'arbre et les droits (mêmes règles que l'ancienne interface : le droit requis doit
 * être détenu sur TOUS les éléments sélectionnés) :
 *  - « Mes documents » : tout, l'usager est propriétaire ;
 *  - « Documents partagés » : copier et télécharger (lecture) ; renommer (un seul), partager (un seul), déplacer et mettre à la
 *    corbeille, déplacer vers NextCloud avec le droit de gestion ; la corbeille y demande une confirmation, l'élément disparaît
 *    pour tous ceux qui le voient ;
 *  - « Documents ajoutés dans les applis » : copier, télécharger ; renommer, corbeille et déplacer vers NextCloud avec le droit de gestion ;
 *  - corbeille : restaurer, supprimer définitivement ;
 *  - autres arbres (publics) : copier dans mes documents et télécharger.
 * « Historique des versions » (un seul document) et « Commentaires » (un seul élément) en « Mes documents » et « Documents partagés ».
 * « Publier au portail » : un seul document, par un administrateur ; un document publié se retrouve (et se retire) dans « Documents publics ». Aucune sélection → aucune action.
 */
export const availableActions = (tree: TreeName, selection: WorkspaceElement[], ctx: ActionContext = {}): ActionId[] => {
  if (selection.length === 0) return [];
  if (tree === "trash" && !ctx.searching) return ["restore", "delete"];
  const single = selection.length === 1;
  const actions: ActionId[] = [];
  // droit de gestion sur tout ce qui est sélectionné : acquis dans « Mes documents », à établir ailleurs
  const manager = tree === "owner" || ctx.managerOnAll === true;
  const managed = tree === "owner" || tree === "shared" || tree === "protected";
  if (managed && manager && single) actions.push("rename");
  if ((tree === "owner" || (tree === "shared" && ctx.canManage)) && single) actions.push("share");
  actions.push("copy");
  if ((tree === "owner" || tree === "shared") && manager) actions.push("move");
  if (managed && manager) actions.push("trash");
  actions.push("download");
  if ((tree === "owner" || tree === "shared" || tree === "public") && single && selection[0].eType === "file" && ctx.canPublish) actions.push("publish");
  if ((tree === "owner" || tree === "shared") && single && selection[0].eType === "file") actions.push("history");
  if ((tree === "owner" || tree === "shared") && single) actions.push("comments");
  if (single && ctx.integration?.lool) actions.push("openLool");
  if (single && ctx.integration?.scratch) actions.push("openScratch");
  if (single && ctx.integration?.geogebra) actions.push("openGeogebra");
  const filesOnly = selection.every((e) => e.eType === "file");
  if (ctx.nextcloud && managed && filesOnly) actions.push("ncCopy");
  if (ctx.nextcloud && managed && filesOnly && manager) actions.push("ncMove");
  if (ctx.cloudDrive && managed && filesOnly) actions.push("driveExport");
  if (ctx.searching && single) actions.push("location");
  return actions;
};

/** Tous les dossiers sous `folderId`, à tous les niveaux (jamais de boucle infinie sur une parenté circulaire). */
export const descendantIds = (folders: WorkspaceElement[], folderId: string): Set<string> => {
  const result = new Set<string>();
  const queue = [folderId];
  while (queue.length) {
    const current = queue.pop() as string;
    for (const f of folders) {
      if (f.eParent === current && !result.has(f._id)) {
        result.add(f._id);
        queue.push(f._id);
      }
    }
  }
  return result;
};

/**
 * Dossiers interdits comme destination d'un déplacement : chaque dossier sélectionné et ses sous-dossiers
 * (on ne déplace pas un dossier dans lui-même, cf. workspace.forbidden.move.folder.in.itself).
 */
export const forbiddenTargets = (folders: WorkspaceElement[], selection: WorkspaceElement[]): Set<string> => {
  const forbidden = new Set<string>();
  for (const el of selection.filter((e) => e.eType === "folder")) {
    forbidden.add(el._id);
    descendantIds(folders, el._id).forEach((id) => forbidden.add(id));
  }
  return forbidden;
};

/**
 * Nom saisi pour un renommage : garde l'extension du fichier si l'usager l'a retirée.
 * Ex. ("Texte.docx", "Devoir") → "Devoir.docx" ; ("Texte.docx", "Devoir.docx") → "Devoir.docx" ; dossier → inchangé.
 */
export const renamedWithExtension = (original: WorkspaceElement, typed: string): string => {
  const name = typed.trim();
  if (original.eType === "folder") return name;
  const ext = /\.([A-Za-z0-9]{1,8})$/.exec(original.name)?.[1];
  if (!ext || name.toLowerCase().endsWith(`.${ext.toLowerCase()}`)) return name;
  return `${name}.${ext}`;
};

/** Téléchargement : un seul document → lui-même ; sinon une archive zip des éléments choisis. */
export const downloadUrl = (selection: WorkspaceElement[]): string =>
  selection.length === 1 && selection[0].eType === "file"
    ? `/workspace/document/${selection[0]._id}`
    : `/workspace/document/archive/${selection.map((e) => e._id).join(",")}`;
