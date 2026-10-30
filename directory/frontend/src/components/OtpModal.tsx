import { Modal } from "@open-ent/react";
import { useTranslation } from "react-i18next";

/** Identifiants de connexion temporaires pour l'application mobile (mot de passe à usage unique, valable 10 minutes). */
export const OtpModal = ({ login, otp, onClose }: { login: string; otp: string | null; onClose: () => void }) => {
  const { t } = useTranslation("directory");
  return (
    <Modal id="account-otp" isOpen={!!otp} onModalClose={onClose}>
      <Modal.Header onModalClose={onClose}>{t("directory.otp.title")}</Modal.Header>
      <Modal.Body>
        <p>{t("directory.otp.content")}</p>
        <p className="fw-bold" data-otp-value>
          {t("directory.otp.login", { login, otp })}
        </p>
        <p className="mb-0">{t("directory.otp.info")}</p>
      </Modal.Body>
    </Modal>
  );
};
