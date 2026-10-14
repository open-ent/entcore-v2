import { odeServices } from '@open-ent/client';
import { VacationSettings, VacationStatus } from '~/models/vacation';

/**
 * Service du message d'absence automatique.
 *
 * Voir le backend conversation `VacationController` : les réglages sont ceux de l'usager
 * connecté, l'identifiant vient toujours de la session.
 */
export const createVacationService = (baseURL: string) => ({
  /** Réglages de l'usager courant, avec l'indicateur « absence en cours ». */
  getSettings() {
    return odeServices.http().get<VacationStatus>(`${baseURL}/vacation`);
  },

  /** Enregistre les réglages et renvoie le statut recalculé. */
  saveSettings(settings: VacationSettings) {
    return odeServices
      .http()
      .put<VacationStatus>(`${baseURL}/vacation`, settings);
  },

  /** Désactive l'absence sans perdre le texte déjà saisi. */
  disable() {
    return odeServices.http().delete(`${baseURL}/vacation`);
  },
});
