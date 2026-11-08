import { Alert, Button } from "@open-ent/react";
import { useTranslation } from "react-i18next";
import { formatSize } from "../lib/elements";
import { Quota } from "../lib/types";

/** À partir de 90 % d'occupation on prévient ; à 100 % on ne peut plus rien importer, le ton passe à l'alerte. */
export const WARN_RATIO = 0.9;

/** Part de l'espace occupée, entre 0 et 1. Un quota nul ou absent ne déclenche rien. */
export const quotaRatio = (quota?: Quota): number =>
  !quota || quota.quota <= 0 ? 0 : quota.storage / quota.quota;

/**
 * Bandeau en tête de l'espace documentaire quand il est presque ou complètement plein, avec de quoi y remédier :
 * retrouver les fichiers les plus lourds, ou vider la corbeille.
 */
export const QuotaAlert = ({
  quota,
  onShowHeaviest,
  onEmptyTrash,
}: {
  quota?: Quota;
  onShowHeaviest: () => void;
  onEmptyTrash: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const ratio = quotaRatio(quota);
  if (!quota || ratio < WARN_RATIO) return null;
  const full = ratio >= 1;
  const missing = Math.max(0, quota.storage - quota.quota);
  // `Alert` ne reporte pas les attributs qu'on lui passe : le repère de test est porté par son conteneur.
  return (
    <div data-workspace-quota-alert={full ? "full" : "near"}>
      <Alert
        type={full ? "danger" : "warning"}
        className="mb-16"
        button={
          <div className="d-flex flex-wrap gap-8">
            <Button
              type="button"
              color="danger"
              variant="outline"
              size="sm"
              data-quota-heaviest
              onClick={onShowHeaviest}
            >
              {t("workspace.react.quota.heaviest")}
            </Button>
            <Button
              type="button"
              color="tertiary"
              variant="outline"
              size="sm"
              data-quota-empty-trash
              onClick={onEmptyTrash}
            >
              {t("workspace.empty.trash")}
            </Button>
          </div>
        }
      >
        <strong className="d-block">
          {full
            ? t("workspace.react.quota.full.title")
            : t("workspace.react.quota.near.title")}
        </strong>
        <span>
          {full
            ? t("workspace.react.quota.full.text", {
                missing: formatSize(missing),
              })
            : t("workspace.react.quota.near.text", {
                left: formatSize(quota.quota - quota.storage),
              })}
        </span>
      </Alert>
    </div>
  );
};
