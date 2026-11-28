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

/**
 * Fiche ouverte par l'ancre de l'adresse, format des liens de notification et de l'ancienne interface :
 * `/userbook/annuaire#<id>#<Profil>` (le profil peut manquer). Toute autre ancre (`#/…`) est ignorée.
 */
export const userFromHash = (hash: string): Visible | null => {
  const m = /^#([0-9a-zA-Z][0-9a-zA-Z-]{7,})(?:#([A-Za-z]*))?$/.exec(decodeURIComponent(hash ?? ""));
  if (!m) return null;
  return { id: m[1], displayName: "", profile: m[2] || undefined };
};

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

/**
 * Regroupe une liste déjà triée par initiale du nom (sans accent, en majuscule), pour l'affichage en carnet d'adresses.
 * Un nom qui ne commence pas par une lettre est rangé sous « # », placé en tête.
 */
export const groupByInitial = <T>(list: T[], nameOf: (item: T) => string): Array<{ letter: string; items: T[] }> => {
  const sections = new Map<string, T[]>();
  for (const item of list) {
    const first = (nameOf(item) ?? "").trim().normalize("NFD").replace(/[̀-ͯ]/g, "").charAt(0).toUpperCase();
    const letter = /[A-Z]/.test(first) ? first : "#";
    if (!sections.has(letter)) sections.set(letter, []);
    sections.get(letter)?.push(item);
  }
  return [...sections.entries()]
    .sort(([a], [b]) => (a === "#" ? -1 : b === "#" ? 1 : a.localeCompare(b)))
    .map(([letter, items]) => ({ letter, items }));
};
