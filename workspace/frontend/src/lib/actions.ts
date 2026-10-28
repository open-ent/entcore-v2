import { TreeName, WorkspaceElement } from "./types";

export type ActionId = "rename" | "copy" | "move" | "trash" | "restore" | "delete" | "download" | "share" | "publish" | "history" | "comments" | "location" | "openLool" | "openScratch" | "openGeogebra";

/** Ce qui conditionne certaines actions : droit de gestion sur la sélection, profil habilité à publier au portail. */
export interface ActionContext {
  /** Vrai si l'usager gère (partage) l'élément sélectionné — toujours vrai dans « Mes documents ». */
  canManage?: boolean;
  /** Administrateur local ou super-administrateur : peut publier un document sur le portail public. */
  canPublish?: boolean;
  /** Résultats de recherche affichés : propose d'ouvrir l'emplacement d'un résultat. */
  searching?: boolean;
  /** Intégrations disponibles pour le document sélectionné (éditeur en ligne, Scratch, GeoGebra). */
  integration?: { lool?: boolean; scratch?: boolean; geogebra?: boolean };
}

/**
 * Actions proposées pour la sélection, selon l'arbre :
 *  - « Mes documents » : renommer (un seul), partager (un seul), copier, déplacer, mettre à la corbeille, télécharger ;
 *  - « Documents partagés » : partager (un seul, si gestionnaire), copier dans mes documents, télécharger — déplacer, renommer et
 *    supprimer y dépendent d'autres droits, non repris ici ;
 *  - corbeille : restaurer, supprimer définitivement ;
 *  - autres arbres (applis, publics) : copier dans mes documents et télécharger.
 * « Historique des versions » (un seul document) et « Commentaires » (un seul élément) en « Mes documents » et « Documents partagés ».
 * « Publier au portail » : un seul document, par un administrateur. Aucune sélection → aucune action.
 */
export const availableActions = (tree: TreeName, selection: WorkspaceElement[], ctx: ActionContext = {}): ActionId[] => {
  if (selection.length === 0) return [];
  if (tree === "trash" && !ctx.searching) return ["restore", "delete"];
  const single = selection.length === 1;
  const actions: ActionId[] = [];
  if (tree === "owner" && single) actions.push("rename");
  if ((tree === "owner" || (tree === "shared" && ctx.canManage)) && single) actions.push("share");
  actions.push("copy");
  if (tree === "owner") actions.push("move", "trash");
  actions.push("download");
  if ((tree === "owner" || tree === "shared") && single && selection[0].eType === "file" && ctx.canPublish) actions.push("publish");
  if ((tree === "owner" || tree === "shared") && single && selection[0].eType === "file") actions.push("history");
  if ((tree === "owner" || tree === "shared") && single) actions.push("comments");
  if (single && ctx.integration?.lool) actions.push("openLool");
  if (single && ctx.integration?.scratch) actions.push("openScratch");
  if (single && ctx.integration?.geogebra) actions.push("openGeogebra");
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
