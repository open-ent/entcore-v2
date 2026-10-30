import { Button, Modal } from "@open-ent/react";
import { FormEvent, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

/** Fenêtre de modification d'un champ texte : validation locale avant l'envoi, erreur du serveur affichée sous le champ. */
export const EditFieldModal = ({
  id,
  title,
  label,
  initial,
  open,
  type = "text",
  maxLength,
  validate,
  onSave,
  onClose,
}: {
  id: string;
  title: string;
  label: string;
  initial: string;
  open: boolean;
  type?: "text" | "tel";
  maxLength?: number;
  /** Renvoie le message d'erreur à afficher, ou `null` si la valeur convient. */
  validate?: (value: string) => string | null;
  /** Enregistre ; rejette avec un message pour l'afficher dans la fenêtre. */
  onSave: (value: string) => Promise<void>;
  onClose: () => void;
}) => {
  const { t } = useTranslation("directory");
  const [value, setValue] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (open) {
      setValue(initial);
      setError(null);
    }
  }, [open, initial]);

  const invalid = validate?.(value) ?? null;
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (invalid || busy) return;
    setBusy(true);
    try {
      await onSave(value);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : t("account.error.save", { defaultValue: "L'enregistrement a échoué." }));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal id={id} isOpen={open} onModalClose={onClose} focusId={`${id}-input`}>
      <Modal.Header onModalClose={onClose}>{title}</Modal.Header>
      <form onSubmit={submit} data-edit-form={id}>
        <Modal.Body>
          <label htmlFor={`${id}-input`} className="d-block mb-8">
            {label}
          </label>
          <input
            id={`${id}-input`}
            className="form-control"
            type={type}
            value={value}
            maxLength={maxLength}
            onChange={(e) => {
              setValue(e.target.value);
              setError(null);
            }}
            data-edit-input
          />
          {(invalid || error) && (
            <p className="text-danger mt-8 mb-0" role="alert" data-edit-error>
              {error ?? invalid}
            </p>
          )}
        </Modal.Body>
        <Modal.Footer>
          <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
            {t("cancel")}
          </Button>
          <Button type="submit" color="primary" disabled={!!invalid || busy} data-edit-save>
            {t("directory.save")}
          </Button>
        </Modal.Footer>
      </form>
    </Modal>
  );
};
