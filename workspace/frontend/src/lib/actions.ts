import { TreeName, WorkspaceElement } from "./types";

export type ActionId = "rename" | "copy" | "move" | "trash" | "restore" | "delete" | "download";

/**
 * Actions proposées pour la sélection, selon l'arbre :
 *  - « Mes documents » : renommer (un seul), copier, déplacer, mettre à la corbeille, télécharger ;
 *  - corbeille : restaurer, supprimer définitivement ;
 *  - autres arbres (partagés, applis, publics) : copier dans mes documents et télécharger — déplacer, renommer et
 *    supprimer y dépendent des droits de partage (lot W4).
 * Aucune sélection → aucune action.
 */
export const availableActions = (tree: TreeName, selection: WorkspaceElement[]): ActionId[] => {
  if (selection.length === 0) return [];
  if (tree === "trash") return ["restore", "delete"];
  if (tree === "owner") return [...(selection.length === 1 ? (["rename"] as ActionId[]) : []), "copy", "move", "trash", "download"];
  return ["copy", "download"];
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
