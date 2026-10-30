import { Button, Modal } from "@open-ent/react";
import { FormEvent, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { complexityLevel, complexityPercent, passwordProblem } from "../lib/password";
import { accountService } from "../services/account.service";

const COLORS = { weak: "#d9534f", moderate: "#f0ad4e", strong: "#5cb85c" } as const;

/** Changement de mot de passe : ancien mot de passe, nouveau (règle de la plateforme) et confirmation, avec jauge de robustesse. */
export const PasswordModal = ({
  open,
  login,
  regex,
  rule,
  onClose,
  onDone,
}: {
  open: boolean;
  login: string;
  regex: string;
  /** Texte de la règle de la plateforme, affiché sous le champ. */
  rule?: string;
  onClose: () => void;
  onDone: () => void;
}) => {
  const { t } = useTranslation("directory");
  const [oldPassword, setOld] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (open) {
      setOld("");
      setPassword("");
      setConfirm("");
      setError(null);
    }
  }, [open]);

  const problem = passwordProblem(oldPassword, password, confirm, regex);
  const level = complexityLevel(password);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (problem || busy) return;
    setBusy(true);
    try {
      await accountService.changePassword(login, oldPassword, password, confirm);
      onDone();
      onClose();
    } catch (err) {
      setError((err as Error).message && (err as Error).message !== "error" ? (err as Error).message : t("userbook.renewpassword.error"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal id="account-password" isOpen={open} onModalClose={onClose} focusId="account-password-old">
      <Modal.Header onModalClose={onClose}>{t("auth.reset.header")}</Modal.Header>
      <form onSubmit={submit} data-password-form autoComplete="off">
        <Modal.Body>
          <label htmlFor="account-password-old" className="d-block mb-4">
            {t("auth.old.password")}
          </label>
          <input id="account-password-old" type="password" className="form-control mb-16" value={oldPassword} onChange={(e) => setOld(e.target.value)} autoComplete="current-password" data-password-old />
          <label htmlFor="account-password-new" className="d-block mb-4">
            {t("auth.activation.password")}
          </label>
          <input id="account-password-new" type="password" className="form-control" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" data-password-new />
          {rule && <small className="d-block text-muted mt-4">{rule}</small>}
          {password && problem === "pattern" && (
            <p className="text-danger mt-8 mb-0" role="alert" data-password-error="pattern">
              {t("password.errors")}
            </p>
          )}
          <label htmlFor="account-password-confirm" className="d-block mt-16 mb-4">
            {t("auth.activation.confirm.password")}
          </label>
          <input id="account-password-confirm" type="password" className="form-control" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" data-password-confirm />
          {confirm && password !== confirm && (
            <p className="text-danger mt-8 mb-0" role="alert" data-password-error="different">
              {t("password.identical")}
            </p>
          )}
          <div className="mt-16" data-password-complexity={level}>
            <small>
              {t("complexity")} : {t(level)}
            </small>
            <div style={{ background: "#e9ecef", height: 8, borderRadius: 4 }}>
              <div style={{ width: `${complexityPercent(password)}%`, background: COLORS[level], height: 8, borderRadius: 4 }} />
            </div>
            <small className="text-muted">{t("password.recommandations")}</small>
          </div>
          {error && (
            <p className="text-danger mt-16 mb-0" role="alert" data-password-error="server">
              {error}
            </p>
          )}
        </Modal.Body>
        <Modal.Footer>
          <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
            {t("cancel")}
          </Button>
          <Button type="submit" color="primary" disabled={!!problem || busy} data-password-save>
            {t("auth.reset")}
          </Button>
        </Modal.Footer>
      </form>
    </Modal>
  );
};
