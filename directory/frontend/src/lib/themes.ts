export interface ThemeEntry {
  parent: string;
  child: string;
  group?: string;
}

/**
 * Lit le fichier `theme-conf.js` (`exports.conf = { overriding: [...] }`) sans l'exécuter : on isole le littéral d'objet puis on
 * l'interprète comme du JSON laxiste (clés non quotées, virgules finales, commentaires).
 */
export const parseThemeConf = (source: string): ThemeEntry[] => {
  const start = source.indexOf("overriding");
  if (start < 0) return [];
  const entries: ThemeEntry[] = [];
  // chaque entrée porte au minimum `parent:` et `child:` entre guillemets
  const re = /\{[^{}]*?parent:\s*"([^"]+)"[^{}]*?child:\s*"([^"]+)"(?:[^{}]*?group:\s*"([^"]+)")?[^{}]*\}/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(source.slice(start)))) entries.push({ parent: m[1], child: m[2], group: m[3] });
  return entries;
};

/** Thèmes proposés à l'utilisateur : ceux du groupe du thème courant, ou tous si ce thème n'a pas de groupe. */
export const themesOfCurrent = (entries: ThemeEntry[], current: string): ThemeEntry[] => {
  const cur = entries.find((e) => e.child === current);
  if (!cur) return [];
  return cur.group ? entries.filter((e) => e.group === cur.group) : entries;
};

/** Le choix n'est proposé que s'il y a de quoi choisir. */
export const canPickTheme = (themes: ThemeEntry[]): boolean => themes.length > 1;
