import { Alert, Flex } from '@open-ent/react';
import { useI18n } from '~/hooks/useI18n';

export interface MessageEditHeaderAlertsProps {
  hasAlertOnGroups: boolean;
  hasRecipientsNotVisibleAlert: boolean;
  /** Vrai si l'élève est hors des horaires d'utilisation (lecture seule, envoi bloqué). */
  hoursClosed?: boolean;
}

export function MessageEditHeaderAlerts({
  hasAlertOnGroups,
  hasRecipientsNotVisibleAlert,
  hoursClosed,
}: MessageEditHeaderAlertsProps) {
  const { t } = useI18n();

  if (!hasAlertOnGroups && !hasRecipientsNotVisibleAlert && !hoursClosed) {
    return null;
  }

  return (
    <Flex className="mx-16 mt-12" gap="12" direction="column">
      {hoursClosed && (
        <Alert type="warning">
          <p>{t('conversation.error.messaging.hours.closed')}</p>
        </Alert>
      )}
      {hasAlertOnGroups && (
        <Alert type="warning" isDismissible={true}>
          <p>{t('conversation.warn.message.grouped')}</p>
        </Alert>
      )}
      {hasRecipientsNotVisibleAlert && (
        <Alert type="warning" isDismissible>
          <p>{t('conversation.edit.warn.no.visible.recipients')}</p>
        </Alert>
      )}
    </Flex>
  );
}
