export interface Filters {
  structures?: string[];
  classes?: string[];
  profiles?: string[];
  functions?: string[];
  positions?: string[];
}

/** Corps de la recherche d'utilisateurs visibles (POST /communication/visible) : seuls les filtres renseignés sont envoyés. */
export const searchBody = (search: string, filters: Filters) => {
  const body: Record<string, unknown> = { search: search.trim().toLowerCase(), types: ["User"], mood: true };
  (Object.keys(filters) as Array<keyof Filters>).forEach((k) => {
    const v = filters[k];
    if (v && v.length > 0) body[k] = v;
  });
  return body;
};

export interface Visible {
  id: string;
  displayName: string;
  profile?: string;
  groupDisplayName?: string | null;
  subjects?: string[];
  positions?: Array<{ id: string; name: string }>;
  mood?: string;
}

/** Tri de la liste des résultats : par nom affiché, sans tenir compte de la casse ni des accents. */
export const sortVisibles = (list: Visible[]): Visible[] => list.slice().sort((a, b) => a.displayName.localeCompare(b.displayName, "fr", { sensitivity: "base" }));

/** Ajoute ou retire une valeur d'une liste de filtres (cases à cocher). */
export const toggleValue = (values: string[] | undefined, value: string): string[] => {
  const cur = values ?? [];
  return cur.includes(value) ? cur.filter((v) => v !== value) : [...cur, value];
};

export const PROFILES = ["Teacher", "Personnel", "Student", "Relative", "Guest"] as const;

export interface VisibleGroup {
  id: string;
  name: string;
  sortName?: string;
  groupType?: string;
  groupDisplayName?: string | null;
}

/** Corps de la recherche de groupes : mêmes critères d'établissement et de classe que pour les personnes. */
export const groupSearchBody = (search: string, filters: Filters) => {
  const body: Record<string, unknown> = { search: search.trim().toLowerCase(), types: ["Group"] };
  (["structures", "classes", "functions"] as const).forEach((k) => {
    const v = filters[k];
    if (v && v.length > 0) body[k] = v;
  });
  return body;
};

/** Tri des groupes : sur le nom de tri s'il existe (il regroupe par établissement puis par type), sinon sur le nom. */
export const sortGroups = (list: VisibleGroup[]): VisibleGroup[] =>
  list.slice().sort((a, b) => (a.sortName ?? a.name).localeCompare(b.sortName ?? b.name, "fr", { sensitivity: "base" }));
