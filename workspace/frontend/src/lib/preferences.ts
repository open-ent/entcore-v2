import { SortKey } from "./elements";
import { ViewMode } from "./types";

/**
 * Préférence « workspace » de l'usager, partagée avec l'ancienne interface (même clé, mêmes champs) : un réglage fait dans l'une
 * vaut pour l'autre. Ex. `{ "view": "list", "sortField": "metadata.size", "sortDesc": true }`.
 */
export interface WorkspacePreference {
  view?: "icons" | "list" | "carousel";
  sortField?: string;
  sortDesc?: boolean;
  [other: string]: unknown;
}

/** Nom du champ de tri dans la préférence (celui de l'ancienne interface) pour chaque critère. */
export const SORT_FIELD: Record<SortKey, string> = { name: "name", owner: "ownerName", modified: "modified", size: "metadata.size" };

export const sortKeyOf = (field?: string): SortKey | undefined => (Object.keys(SORT_FIELD) as SortKey[]).find((k) => SORT_FIELD[k] === field);

/** Affichage retenu : « list » seulement si c'est ce que dit la préférence (les autres modes de l'ancienne interface s'affichent en icônes). */
export const viewOf = (p?: WorkspacePreference): ViewMode => (p?.view === "list" ? "list" : "icons");

/**
 * Nouveau tri après un clic sur un critère : le même critère inverse le sens, un autre critère repart en croissant
 * (comme l'ancienne interface).
 */
export const nextSort = (current: { key: SortKey; desc: boolean }, key: SortKey): { key: SortKey; desc: boolean } =>
  key === current.key ? { key, desc: !current.desc } : { key, desc: false };
