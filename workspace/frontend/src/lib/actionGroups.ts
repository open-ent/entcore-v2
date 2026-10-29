/** Un menu déroulant de la barre de sélection (Partager, Organiser, Plus) : ses actions, dans l'ordre d'affichage. */
export interface MenuLayout<T extends string> {
  key: string;
  /** Clé de traduction du libellé du menu. */
  label: string;
  ids: T[];
}

/** Disposition de la barre de sélection : actions directes, menus, puis actions destructrices poussées à droite. */
export interface BarLayout<T extends string> {
  direct: T[];
  menus: MenuLayout<T>[];
  danger: T[];
}

export type BarEntry<T extends string> = { kind: "button"; id: T } | { kind: "menu"; key: string; label: string; ids: T[] };

export interface GroupedBar<T extends string> {
  entries: BarEntry<T>[];
  danger: T[];
}

/**
 * Range les actions disponibles selon la disposition : une action directe reste un bouton ; un menu ne se montre que s'il contient au
 * moins une action possible, et avec UNE seule il redevient un simple bouton (un menu d'un seul choix n'aide personne).
 * Ex. Mes documents, un fichier : [télécharger, renommer, Partager ▾ (partager, publier), copier, Plus ▾ (versions, commentaires), corbeille].
 */
export const groupActions = <T extends string>(available: T[], layout: BarLayout<T>): GroupedBar<T> => {
  const has = (id: T) => available.includes(id);
  const entries: BarEntry<T>[] = layout.direct.filter(has).map((id) => ({ kind: "button", id }));
  for (const m of layout.menus) {
    const ids = m.ids.filter(has);
    if (ids.length === 1) entries.push({ kind: "button", id: ids[0] });
    else if (ids.length > 1) entries.push({ kind: "menu", key: m.key, label: m.label, ids });
  }
  return { entries, danger: layout.danger.filter(has) };
};
