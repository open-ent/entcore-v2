import { Button } from '@open-ent/react';
import { IconClock } from '@open-ent/react/icons';
import { useRef } from 'react';

import { useI18n } from '~/hooks/useI18n';
import {
  useCancelScheduledMessage,
  useRescheduleMessage,
  useScheduledMessages,
} from '~/services';
import { useConfirmModalStore } from '~/store';

/** Forme renvoyée par l'endpoint backend GET /conversation/scheduled. */
interface ScheduledItem {
  id: string;
  subject?: string;
  scheduled_at?: number;
}

/** Formate une Date en valeur d'<input type="datetime-local"> (heure locale). */
function toDatetimeLocalValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

export function Component() {
  const { t } = useI18n();
  const { data, isPending } = useScheduledMessages();
  const messages = (data ?? []) as unknown as ScheduledItem[];
  const cancelMutation = useCancelScheduledMessage();
  const rescheduleMutation = useRescheduleMessage();
  const { openModal } = useConfirmModalStore();
  const rescheduleRef = useRef<HTMLInputElement>(null);

  const onReschedule = (item: ScheduledItem) => {
    const current = item.scheduled_at
      ? new Date(item.scheduled_at)
      : new Date(Date.now() + 60 * 60 * 1000);
    openModal({
      id: 'reschedule-modal',
      header: <>{t('conversation.scheduled.reschedule')}</>,
      body: (
        <input
          ref={rescheduleRef}
          type="datetime-local"
          className="form-control"
          defaultValue={toDatetimeLocalValue(current)}
          min={toDatetimeLocalValue(new Date(Date.now() + 60 * 1000))}
        />
      ),
      okText: t('conversation.scheduled.modal.confirm'),
      koText: t('cancel'),
      onSuccess: async () => {
        const value = rescheduleRef.current?.value;
        const at = value ? new Date(value).getTime() : NaN;
        if (!at || Number.isNaN(at) || at <= Date.now()) return;
        rescheduleMutation.mutate({ messageId: item.id, scheduledAt: at });
      },
    });
  };

  const onCancel = (item: ScheduledItem) => {
    openModal({
      id: 'cancel-schedule-modal',
      header: <>{t('conversation.scheduled.cancel')}</>,
      body: <p>{t('conversation.scheduled.canceled')}</p>,
      okText: t('confirm'),
      koText: t('cancel'),
      onSuccess: async () => {
        cancelMutation.mutate({ messageId: item.id });
      },
    });
  };

  return (
    <div className="p-16 w-100">
      <h2 className="mb-16 d-flex align-items-center gap-8">
        <IconClock /> {t('conversation.scheduled.folder')}
      </h2>

      {isPending ? (
        <div className="d-flex justify-content-center p-32">
          <div className="spinner-border" role="status">
            <span className="visually-hidden">{t('loading')}</span>
          </div>
        </div>
      ) : messages.length === 0 ? (
        <div className="text-secondary p-16 text-center">
          {t('conversation.scheduled.empty')}
        </div>
      ) : (
        <ul className="list-unstyled m-0 d-flex flex-column gap-8">
          {messages.map((m) => (
            <li
              key={m.id}
              className="card p-12 d-flex flex-row align-items-center justify-content-between"
            >
              <div className="min-w-0">
                <div className="fw-bold text-truncate">
                  {m.subject || t('nosubject')}
                </div>
                <div className="small text-secondary">
                  {t('conversation.scheduled.sendAt')}{' '}
                  {m.scheduled_at
                    ? new Date(m.scheduled_at).toLocaleString()
                    : '—'}
                </div>
              </div>
              <div className="d-flex gap-8 flex-shrink-0">
                <Button
                  variant="ghost"
                  color="tertiary"
                  onClick={() => onReschedule(m)}
                >
                  {t('conversation.scheduled.reschedule')}
                </Button>
                <Button
                  variant="ghost"
                  color="tertiary"
                  onClick={() => onCancel(m)}
                >
                  {t('conversation.scheduled.cancel')}
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
