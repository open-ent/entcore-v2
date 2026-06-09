import { odeServices } from '@open-ent/client';
import { useQuery } from '@tanstack/react-query';
import { baseUrl } from '~/services/api';

/**
 * Statut des horaires d'utilisation de la messagerie pour l'utilisateur courant.
 * Voir le backend conversation `GET /conversation/messaging-hours` (MessagingHoursController).
 * Seuls les élèves (`restricted: true`) sont soumis aux horaires ; `open: false` signifie
 * « hors plage » → la messagerie est en lecture seule (l'envoi est bloqué côté serveur).
 */
export interface MessagingHoursStatus {
  open: boolean;
  restricted: boolean;
  schedule?: {
    start?: string;
    end?: string;
    days?: number[];
  } | null;
}

export function useMessagingHours() {
  const { data } = useQuery({
    queryKey: ['messaging-hours'],
    queryFn: () =>
      odeServices
        .http()
        .get<MessagingHoursStatus>(`${baseUrl}/messaging-hours`),
    staleTime: 1000 * 60,
  });

  // Fermé = l'utilisateur est soumis aux horaires (élève) ET on est hors plage.
  const closed = !!(data?.restricted && data?.open === false);

  return { status: data, closed };
}
