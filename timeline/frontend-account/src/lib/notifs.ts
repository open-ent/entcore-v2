export type Frequency = "IMMEDIATE" | "DAILY" | "WEEKLY" | "NEVER";
export const FREQUENCIES: Frequency[] = [
  "IMMEDIATE",
  "DAILY",
  "WEEKLY",
  "NEVER",
];

/** Une notification configurable, telle que la renvoie `/timeline/notifications-defaults`. */
export interface Default {
  key: string;
  type: string;
  "app-name"?: string;
  "app-address"?: string;
  "event-type"?: string;
  defaultFrequency?: Frequency;
  restriction?: string;
}

export interface AppGroup {
  type: string;
  appName?: string;
  actions: Default[];
}

/**
 * Regroupe les notifications par application. Écarte celles réservées au fil (INTERNAL) ou masquées (HIDDEN), et celles des
 * applications que l'utilisateur n'a pas (`apps` = noms d'applications de sa session).
 */
export const groupByApp = (defaults: Default[], apps: string[]): AppGroup[] => {
  const mine = apps.map((a) => a.toLowerCase());
  const kept = defaults.filter(
    (n) =>
      n.restriction !== "INTERNAL" &&
      n.restriction !== "HIDDEN" &&
      (mine.includes(n.type.toLowerCase()) ||
        (!!n["app-name"] && mine.includes(n["app-name"].toLowerCase()))),
  );
  const groups = new Map<string, AppGroup>();
  kept.forEach((n) => {
    const g = groups.get(n.type) ?? {
      type: n.type,
      appName: n["app-name"],
      actions: [],
    };
    g.actions.push(n);
    groups.set(n.type, g);
  });
  return Array.from(groups.values());
};

/** Applique les préférences enregistrées (`{config: {<clé>: {defaultFrequency}}}`) aux fréquences par défaut. */
export const withPreferences = (
  groups: AppGroup[],
  preference: {
    config?: Record<string, { defaultFrequency?: Frequency }>;
  } | null,
): AppGroup[] =>
  groups.map((g) => ({
    ...g,
    actions: g.actions.map((a) => {
      const saved = preference?.config?.[a.key]?.defaultFrequency;
      return saved ? { ...a, defaultFrequency: saved } : a;
    }),
  }));

/** Fréquence commune à toutes les notifications d'une application, ou `undefined` si elles diffèrent (aucune case cochée en tête). */
export const commonFrequency = (g: AppGroup): Frequency | undefined => {
  if (g.actions.length === 0) return undefined;
  const first = g.actions[0].defaultFrequency;
  return g.actions.every((a) => a.defaultFrequency === first)
    ? first
    : undefined;
};

export const setGroupFrequency = (
  groups: AppGroup[],
  type: string,
  freq: Frequency,
): AppGroup[] =>
  groups.map((g) =>
    g.type === type
      ? {
          ...g,
          actions: g.actions.map((a) => ({ ...a, defaultFrequency: freq })),
        }
      : g,
  );

export const setActionFrequency = (
  groups: AppGroup[],
  key: string,
  freq: Frequency,
): AppGroup[] =>
  groups.map((g) => ({
    ...g,
    actions: g.actions.map((a) =>
      a.key === key ? { ...a, defaultFrequency: freq } : a,
    ),
  }));

/** Corps de l'enregistrement : les préférences existantes, avec la fréquence de chaque notification affichée. */
export const preferenceBody = (
  previous: {
    config?: Record<string, { defaultFrequency?: Frequency }>;
  } | null,
  groups: AppGroup[],
) => {
  const config: Record<string, { defaultFrequency?: Frequency }> = {
    ...(previous?.config ?? {}),
  };
  groups.forEach((g) =>
    g.actions.forEach((a) => {
      config[a.key] = {
        ...(config[a.key] ?? {}),
        defaultFrequency: a.defaultFrequency,
      };
    }),
  );
  return { ...(previous ?? {}), config };
};
