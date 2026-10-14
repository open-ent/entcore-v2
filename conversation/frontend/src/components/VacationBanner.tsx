import { Alert, Button } from '@open-ent/react';
import { useI18n } from '~/hooks/useI18n';
import { useDisableVacation, useVacationSettings } from '~/services';
import { useActionsStore } from '~/store/actions';

/**
 * Bandeau rappelant que le message d'absence est ACTIF.
 *
 * Il n'apparaît que lorsque les réponses automatiques partent réellement (absence activée
 * ET période en cours) : c'est le seul moment où l'usager a besoin d'être prévenu, et le
 * seul où la désactivation a un effet.
 */
export function VacationBanner() {
  const { t } = useI18n();
  const { data } = useVacationSettings();
  const disableMutation = useDisableVacation();
  const setOpenedModal = useActionsStore.use.setOpenedModal();

  if (!data?.active) return null;

  return (
    <Alert type="info" className="mb-12">
      <div className="d-flex flex-wrap align-items-center gap-8">
        <span className="flex-fill">{t('vacation.banner.active')}</span>
        <Button
          type="button"
          color="tertiary"
          variant="ghost"
          onClick={() => setOpenedModal('vacation')}
          data-testid="buttonVacationBannerEdit"
        >
          {t('vacation.banner.edit')}
        </Button>
        <Button
          type="button"
          color="tertiary"
          variant="ghost"
          isLoading={disableMutation.isPending}
          onClick={() => disableMutation.mutate()}
          data-testid="buttonVacationBannerDisable"
        >
          {t('vacation.banner.disable')}
        </Button>
      </div>
    </Alert>
  );
}
