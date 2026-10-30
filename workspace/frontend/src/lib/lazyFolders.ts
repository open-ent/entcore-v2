import { WorkspaceElement } from "./types";

/**
 * Ajoute des dossiers à ceux déjà connus, sans doublon : un dossier reçu une seconde fois remplace l'ancien.
 * Ex. [Cours, Archives] + [Archives (renommé), 6e] → [Cours, Archives (renommé), 6e].
 */
export const mergeFolders = (current: WorkspaceElement[], incoming: WorkspaceElement[]): WorkspaceElement[] => {
  const byId = new Map(current.map((f) => [f._id, f]));
  for (const f of incoming) byId.set(f._id, f);
  return [...byId.values()];
};

/**
 * Mode « lazy » : un dossier dont les enfants ne sont pas encore chargés doit pouvoir se déplier (on ne sait pas s'il en a).
 * Une fois chargés, il ne se déplie que s'il en a réellement.
 * Ex. Cours (jamais ouvert) → true ; Cours (chargé, vide) → false ; Cours (chargé, 2 enfants) → true.
 */
export const canExpand = (hasChildren: boolean, lazy: boolean, loaded: boolean): boolean => hasChildren || (lazy && !loaded);
