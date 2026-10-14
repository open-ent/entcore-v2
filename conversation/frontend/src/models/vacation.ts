/**
 * Message d'absence automatique.
 *
 * Les bornes sont des dates civiles (`YYYY-MM-DD`), sans heure : l'absence court du premier
 * au dernier jour inclus, dans le fuseau de l'établissement. Une borne absente signifie
 * « pas de limite de ce côté ».
 */
export type VacationSettings = {
  /** L'absence est paramétrée et activée par l'usager. */
  enabled: boolean;
  /** Texte riche renvoyé aux expéditeurs. */
  body: string;
  startDate?: string | null;
  endDate?: string | null;
};

export type VacationStatus = VacationSettings & {
  /** La fonctionnalité est ouverte sur cette plateforme. */
  featureEnabled: boolean;
  /** L'absence est activée ET nous sommes dans sa période : les réponses partent. */
  active: boolean;
};
