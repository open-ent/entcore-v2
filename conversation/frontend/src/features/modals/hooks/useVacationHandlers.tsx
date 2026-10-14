import { VacationSettings } from '~/models/vacation';
import { useDisableVacation, useSaveVacationSettings } from '~/services';
import { useActionsStore } from '~/store/actions';

/**
 * Actions du message d'absence : enregistrement et désactivation.
 */
export const useVacationHandlers = () => {
  const setOpenedModal = useActionsStore.use.setOpenedModal();
  const saveMutation = useSaveVacationSettings();
  const disableMutation = useDisableVacation();

  const closeModal = () => setOpenedModal(undefined);

  return {
    isSaving: saveMutation.isPending,
    isDisabling: disableMutation.isPending,
    /** Ferme la modale de paramétrage. */
    closeModal,
    /** Enregistre les réglages (texte, période, activation). */
    save: (settings: VacationSettings) => saveMutation.mutateAsync(settings),
    /** Désactive l'absence en conservant le texte saisi. */
    disable: () => disableMutation.mutateAsync(),
  };
};
