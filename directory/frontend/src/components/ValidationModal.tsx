import { Modal } from "@open-ent/react";
import { useTranslation } from "react-i18next";

/** Validation d'un courriel ou d'un mobile : on affiche, en cadre, la page du module d'authentification (code reçu puis confirmation). */
export const ValidationModal = ({ kind, onClose }: { kind: "email" | "sms" | null; onClose: () => void }) => {
  const { t } = useTranslation("directory");
  return (
    <Modal id="account-validate" isOpen={!!kind} onModalClose={onClose}>
      <Modal.Header onModalClose={onClose}>{t(kind === "sms" ? "userBook.profile.mobilePhone" : "userBook.profile.email")}</Modal.Header>
      <Modal.Body>
        {kind && (
          <iframe
            title={t(kind === "sms" ? "userBook.profile.mobilePhone" : "userBook.profile.email")}
            src={`/auth/validate-mail?headless=true&type=${kind}`}
            style={{ height: 400, width: "100%", border: "none" }}
            data-validate-frame={kind}
          />
        )}
      </Modal.Body>
    </Modal>
  );
};
