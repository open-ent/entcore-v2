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

/**
 * Tris proposés dans la liste déroulante « Trier par » : le critère ET son sens, dans un seul choix, pour éviter
 * d'avoir à comprendre un bouton de direction à part.
 */
export const SORT_OPTIONS: Array<{ value: string; key: SortKey; desc: boolean; labelKey: string }> = [
  { value: "name-asc", key: "name", desc: false, labelKey: "workspace.react.sort.name.asc" },
  { value: "name-desc", key: "name", desc: true, labelKey: "workspace.react.sort.name.desc" },
  { value: "modified-desc", key: "modified", desc: true, labelKey: "workspace.react.sort.modified.desc" },
  { value: "modified-asc", key: "modified", desc: false, labelKey: "workspace.react.sort.modified.asc" },
  { value: "size-desc", key: "size", desc: true, labelKey: "workspace.react.sort.size.desc" },
  { value: "size-asc", key: "size", desc: false, labelKey: "workspace.react.sort.size.asc" },
  { value: "owner-asc", key: "owner", desc: false, labelKey: "workspace.react.sort.owner.asc" },
];

/** Valeur de la liste déroulante pour un tri courant ; le tri par défaut quand la combinaison n'y figure pas. */
export const sortOptionValue = (sort: { key: SortKey; desc: boolean }): string =>
  SORT_OPTIONS.find((o) => o.key === sort.key && o.desc === sort.desc)?.value ?? "name-asc";

/** Tri correspondant à une valeur de la liste déroulante. */
export const sortOf = (value: string): { key: SortKey; desc: boolean } => {
  const option = SORT_OPTIONS.find((o) => o.value === value) ?? SORT_OPTIONS[0];
  return { key: option.key, desc: option.desc };
};
