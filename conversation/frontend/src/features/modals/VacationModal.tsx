import {
  Button,
  FormControl,
  Label,
  Loading,
  Modal,
  Switch,
  useToast,
} from '@open-ent/react';
import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import {
  SignatureEditor,
  SignatureEditorRef,
} from '~/components/SignatureEditor/components/SignatureEditor';
import { useI18n } from '~/hooks/useI18n';
import { useVacationSettings } from '~/services';
import { useVacationHandlers } from './hooks';

/** Longueur du message d'absence : plus généreuse que la signature, mais bornée. */
const VACATION_MAX_LENGTH = 2000;

/**
 * Paramétrage du message d'absence : texte riche, période facultative, activation.
 *
 * L'éditeur est celui de la signature — même socle, mêmes contraintes de mise en forme.
 */
export function VacationModal() {
  const { t, common_t } = useI18n();
  const settingsQuery = useVacationSettings();
  const toast = useToast();

  const { isSaving, closeModal, save } = useVacationHandlers();

  const editor = useRef<SignatureEditorRef>(null);

  const [enabled, setEnabled] = useState(false);
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [isLengthValid, setIsLengthValid] = useState(true);
  const [bodyLength, setBodyLength] = useState(0);

  useEffect(() => {
    if (!settingsQuery.data) return;
    setEnabled(settingsQuery.data.enabled);
    setStartDate(settingsQuery.data.startDate ?? '');
    setEndDate(settingsQuery.data.endDate ?? '');
  }, [settingsQuery.data]);

  const isRangeInvalid = !!startDate && !!endDate && startDate > endDate;
  // Le serveur refuse une absence activée sans texte : on bloque le bouton plutôt que
  // de laisser l'usager découvrir l'erreur à l'enregistrement.
  const isBodyMissing = enabled && bodyLength === 0;

  const lockSaveButton =
    !isLengthValid ||
    isRangeInvalid ||
    isBodyMissing ||
    settingsQuery.isPending ||
    isSaving;

  const handleSaveClick = useCallback(() => {
    async function handleSave() {
      const body = editor.current?.getHtmlContent() ?? '';
      try {
        await save({
          enabled,
          body,
          startDate: startDate || null,
          endDate: endDate || null,
        });
        toast.success(
          enabled ? t('vacation.notify.saved') : t('vacation.notify.disabled'),
        );
        closeModal();
      } catch (error) {
        toast.error(t('vacation.notify.error'));
        console.error('error:', error);
      }
    }
    handleSave();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [closeModal, save, enabled, startDate, endDate]);

  return (
    <Modal size="lg" id="modalVacation" isOpen={true} onModalClose={closeModal}>
      <Modal.Header onModalClose={closeModal}>
        {t('vacation.modal.title')}
      </Modal.Header>

      <Modal.Body>
        <Suspense fallback={<Loading isLoading={settingsQuery.isPending} />}>
          <Switch
            label={t('vacation.modal.toggle.label')}
            checked={enabled}
            onChange={() => setEnabled((previous) => !previous)}
          />
          <p className="my-8 py-4">
            <em>
              <small>{t('vacation.modal.toggle.hint')}</small>
            </em>
          </p>

          <div className="d-flex flex-wrap gap-16 mb-12">
            <div className="flex-fill">
              <FormControl id="vacationStartDate">
                <Label>{t('vacation.modal.start.label')}</Label>
                <input
                  type="date"
                  className="form-control"
                  value={startDate}
                  max={endDate || undefined}
                  onChange={(event) => setStartDate(event.target.value)}
                  data-testid="inputVacationStart"
                />
              </FormControl>
            </div>
            <div className="flex-fill">
              <FormControl id="vacationEndDate">
                <Label>{t('vacation.modal.end.label')}</Label>
                <input
                  type="date"
                  className="form-control"
                  value={endDate}
                  min={startDate || undefined}
                  onChange={(event) => setEndDate(event.target.value)}
                  data-testid="inputVacationEnd"
                />
              </FormControl>
            </div>
          </div>
          <p className="mb-12">
            <em>
              <small>{t('vacation.modal.period.hint')}</small>
            </em>
          </p>

          <SignatureEditor
            ref={editor}
            content={settingsQuery.data?.body ?? ''}
            mode={'edit'}
            maxLength={VACATION_MAX_LENGTH}
            placeholder={t('vacation.modal.editor.placeholder')}
            onLengthChange={(isValid, newLength) => {
              setIsLengthValid(isValid);
              setBodyLength(newLength);
            }}
          />
        </Suspense>
      </Modal.Body>

      <Modal.Footer>
        <Button
          type="button"
          color="tertiary"
          variant="ghost"
          onClick={closeModal}
        >
          {common_t('cancel')}
        </Button>
        <Button
          type="button"
          color="primary"
          variant="filled"
          onClick={handleSaveClick}
          isLoading={isSaving}
          disabled={lockSaveButton}
          data-testid="buttonSaveVacation"
        >
          {common_t('save')}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
