import { FormEvent, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { workspaceService } from "../services/workspace.service";
import { UiPreference } from "../lib/types";

/** Au-delà, l'information a été transmise : continuer à occuper le bas de l'écran serait pénible. */
export const MAX_DISPLAYS = 5;
const SETTINGS_URL = "/dashboard/account/settings";

/** Le bandeau n'a de sens que tant que l'usager n'a fait AUCUN choix dans ses paramètres, n'a pas fermé le bandeau et l'a vu moins de 5 fois. */
export const bannerVisible = (p: UiPreference | undefined, hidden: boolean): boolean =>
  !!p && !hidden && !p.ui && !p.returnDismissed && (p.returnShown ?? 0) < MAX_DISPLAYS;

/**
 * Bandeau de retour d'expérience de la nouvelle interface : « revenir à l'ancienne version » (avec une question en une ligne, le seul
 * moment où l'on saura POURQUOI quelqu'un repart), « Ne plus afficher », lien vers les paramètres du compte. Tant qu'il est affiché,
 * le bouton de bascule lui laisse la place (`onVisibleChange`) ; une fois un choix posé dans les paramètres, il ne revient plus et le
 * bouton reste, lui, en permanence.
 * Ex. réponse stockée : `{ feedback: "il manque le partage NextCloud" }`.
 */
export const UiSwitchBanner = ({ onVisibleChange }: { onVisibleChange?: (visible: boolean) => void }) => {
  const { t } = useTranslation("workspace");
  const qc = useQueryClient();
  const [asking, setAsking] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [hidden, setHidden] = useState(false);
  const pref = useQuery({ queryKey: ["workspace", "ui-preference"], queryFn: workspaceService.getUiPreference, staleTime: Infinity });
  const preference = pref.data;
  const visible = bannerVisible(preference, hidden);

  const save = async (next: UiPreference) => {
    qc.setQueryData(["workspace", "ui-preference"], next);
    await workspaceService.saveUiPreference(next);
  };

  useEffect(() => onVisibleChange?.(visible), [visible, onVisibleChange]);

  // Un affichage compté une seule fois par chargement de page, jamais pendant le rendu.
  const counted = useRef(false);
  useEffect(() => {
    if (!visible || counted.current || !preference) return;
    counted.current = true;
    void save({ ...preference, returnShown: (preference.returnShown ?? 0) + 1 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, preference]);

  if (!visible || !preference) return null;

  const goBack = (withFeedback: boolean) => {
    const answer = feedback.trim();
    void save({ ...preference, ...(withFeedback && answer ? { feedback: answer, feedbackAt: new Date().toISOString() } : {}) }).finally(() => {
      // Aucune préférence `ui` n'est posée : le choix durable se fait dans les paramètres du compte. Dérogation d'adresse seule.
      window.location.href = `${window.location.pathname}?ui=angular`;
    });
  };
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    goBack(true);
  };

  return (
    <div className="workspace-ui-switch card shadow p-16" role="region" aria-label={t("workspace.react.switch.title")} data-ui-banner style={{ position: "fixed", right: 16, bottom: 16, zIndex: 1040, maxWidth: 480 }}>
      {asking ? (
        <form className="d-flex flex-column gap-8" onSubmit={onSubmit}>
          <label htmlFor="workspace-ui-feedback">{t("workspace.react.switch.question")}</label>
          <input id="workspace-ui-feedback" className="form-control" autoFocus placeholder={t("workspace.react.switch.placeholder")} value={feedback} onChange={(e) => setFeedback(e.target.value)} />
          <div className="d-flex justify-content-end gap-8">
            <button type="button" className="btn btn-link" style={{ color: "#243242" }} onClick={() => goBack(false)} data-ui-skip>
              {t("workspace.react.switch.skip")}
            </button>
            <button type="submit" className="btn btn-primary" data-ui-send>
              {t("workspace.react.switch.send")}
            </button>
          </div>
        </form>
      ) : (
        <div className="d-flex align-items-center gap-16 flex-wrap">
          <div className="flex-fill">
            <div>{t("workspace.react.switch.title")}</div>
            <small className="text-muted">
              {t("workspace.react.switch.where")} <a href={SETTINGS_URL}>{t("workspace.react.switch.settings")}</a>.
            </small>
          </div>
          <div className="d-flex align-items-center gap-8">
            <button
              type="button"
              className="btn btn-link"
              style={{ color: "#243242" }}
              data-ui-dismiss
              onClick={() => {
                setHidden(true);
                void save({ ...preference, returnDismissed: true });
              }}
            >
              {t("workspace.react.switch.dismiss")}
            </button>
            <button type="button" className="btn btn-outline-primary" data-ui-back onClick={() => setAsking(true)}>
              {t("workspace.react.switch.back")}
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
