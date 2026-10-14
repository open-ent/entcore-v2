import {
  queryOptions,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { VacationSettings, VacationStatus } from '~/models/vacation';
import { vacationService } from '..';

export const vacationQueryKeys = {
  all: () => ['vacation'] as const,
  settings: () => [...vacationQueryKeys.all(), 'settings'] as const,
};

export const vacationQueryOptions = {
  /** Réglages du message d'absence de l'usager courant. */
  getSettings() {
    return queryOptions({
      queryKey: vacationQueryKeys.settings(),
      queryFn: (): Promise<VacationStatus> => vacationService.getSettings(),
      // La période peut basculer en cours de session (début/fin d'absence) : on rafraîchit
      // assez souvent pour que le bandeau ne mente pas, sans interroger à chaque écran.
      staleTime: 5 * 60 * 1000,
    });
  },
};

export const useVacationSettings = () =>
  useQuery(vacationQueryOptions.getSettings());

export const useSaveVacationSettings = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (settings: VacationSettings) =>
      vacationService.saveSettings(settings),
    onSuccess: (status) => {
      queryClient.setQueryData(vacationQueryKeys.settings(), status);
    },
  });
};

export const useDisableVacation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () => vacationService.disable(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: vacationQueryKeys.all() });
    },
  });
};
