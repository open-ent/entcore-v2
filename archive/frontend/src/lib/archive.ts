/** Rapport d'import d'une application, tel que le serveur le renvoie. */
export interface ImportReport {
  mainResourceName?: string | null;
  resourcesIdsMap?: Record<string, Record<string, unknown>> | null;
  resourcesNumber?: number;
  duplicatesNumberMap?: Record<string, number> | null;
  duplicatesNumber?: number | string;
  errorsNumber?: number;
}

/** Applications dont le serveur ne sait pas dénombrer les doublons : « non applicable » dans le rapport. */
export const NO_DUPLICATE_COUNT_APPS = ["actualites", "schoolbook", "exercizer", "conversation"];

/**
 * Nombre d'éléments importés : ceux de la ressource principale quand le rapport la détaille, sinon le total.
 * Ex. { mainResourceName: "blog", resourcesIdsMap: { blog: { a: 1, b: 2 } }, resourcesNumber: 9 } → 2.
 */
export const countMainResources = (r: ImportReport): number | undefined => {
  const main = r.mainResourceName;
  const ids = r.resourcesIdsMap;
  if (main != null && ids != null) {
    const resources = ids[main];
    if (resources != null) return Object.keys(resources).length;
  }
  return r.resourcesNumber;
};

/** Nombre de doublons de la ressource principale quand le rapport le détaille, sinon le total. */
export const countDuplicateResources = (r: ImportReport): number | string | undefined => {
  const main = r.mainResourceName;
  const dups = r.duplicatesNumberMap;
  if (main != null && dups != null) {
    const d = dups[main];
    if (d != null) return d;
  }
  return r.duplicatesNumber;
};

const SIZE_UNITS = ["import.bytes", "import.kilobytes", "import.megabytes", "import.gigabytes"];

export interface ReadableSize {
  quantity: number;
  /** Clé de traduction de l'unité. */
  unit: string;
}

/**
 * Taille lisible, en unités décimales (1 Ko = 1000 octets), comme l'ancienne interface.
 * Ex. 1 500 000 → { quantity: 1.5, unit: "import.megabytes" } ; 999 → { quantity: 999, unit: "import.bytes" }.
 */
export const readableSize = (bytes: number): ReadableSize => {
  for (let i = 0; i < 4; i++) {
    const v = Math.floor(bytes / Math.pow(1000, i));
    if (v < 1024 || i === 3) return { quantity: Math.round((bytes / Math.pow(1000, i)) * 100) / 100, unit: SIZE_UNITS[i] };
  }
  return { quantity: bytes, unit: SIZE_UNITS[0] };
};

/** Applications que l'on peut exporter : celles de l'usager que l'archivage sait traiter, sans doublon, triées par nom affiché. */
export const exportableApps = (
  myApps: Array<{ prefix?: string; displayName?: string }>,
  exportable: string[],
  label: (key: string) => string,
): string[] =>
  myApps
    .map((a) => (a.prefix ? a.prefix.slice(1) : a.displayName ? a.displayName : "undefined"))
    .filter((app) => exportable.includes(app))
    // le registre peut exposer plusieurs applications pour un même préfixe (ex. /wiki « Cours et Wiki » + « Wiki »)
    .filter((app, i, all) => all.indexOf(app) === i)
    .sort((a, b) => {
      const x = label(a);
      const y = label(b);
      return x < y ? -1 : x > y ? 1 : 0;
    });

export type ExportMode = "docsOnly" | "all" | "apps";

/**
 * Applications à exporter selon le bouton choisi : « documents seulement » (espace documentaire et casier), « tout » (toutes celles
 * que l'archivage connaît, pour un compte dont les applications sont déjà retirées), ou celles cochées.
 */
export const appsToExport = (mode: ExportMode, selected: string[], everything: string[]): string[] =>
  mode === "docsOnly" ? ["workspace", "rack"] : mode === "all" ? everything : selected;

/** « Tout cocher » : tout est coché → on décoche tout, sinon on coche tout. */
export const toggleAll = (available: string[], selected: string[]): string[] => (available.every((a) => selected.includes(a)) ? [] : [...available]);

/** Taille cumulée des applications cochées d'une archive à importer, et dépassement du quota disponible. */
export const selectedImportSize = (apps: Record<string, { size: number }>, selected: string[]): number => selected.reduce((sum, a) => sum + (apps[a]?.size ?? 0), 0);
export const quotaExceeded = (size: number, quota: number): boolean => size > quota;
