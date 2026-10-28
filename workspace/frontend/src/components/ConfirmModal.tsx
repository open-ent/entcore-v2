import { Button, Modal } from "@open-ent/react";
import { useTranslation } from "react-i18next";

/** Confirmation avant une action irréversible (suppression définitive, vidage de la corbeille). */
export const ConfirmModal = ({
  isOpen,
  title,
  message,
  confirmLabel,
  onConfirm,
  onClose,
}: {
  isOpen: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  onConfirm: () => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  return (
    <Modal id="workspace-confirm" isOpen={isOpen} onModalClose={onClose}>
      <Modal.Header onModalClose={onClose}>{title}</Modal.Header>
      <Modal.Body>
        <p>{message}</p>
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
          {t("cancel")}
        </Button>
        <Button type="button" color="danger" onClick={onConfirm} data-confirm-submit>
          {confirmLabel}
        </Button>
      </Modal.Footer>
    </Modal>
  );
};
