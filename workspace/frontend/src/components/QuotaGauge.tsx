import { Button } from "@open-ent/react";
import { useTranslation } from "react-i18next";
import { formatSize } from "../lib/elements";
import { Quota } from "../lib/types";
import { WARN_RATIO } from "./QuotaAlert";

/**
 * Carte « Espace utilisé » au pied de la colonne de gauche : la part occupée en gros, la barre, puis « 154,3 Mo sur 100 Mo ».
 * Au-delà de 100 % la barre reste pleine mais le chiffre, lui, dit la vérité.
 */
export const QuotaGauge = ({ quota, onManage }: { quota: Quota; onManage?: () => void }) => {
  const { t } = useTranslation("workspace");
  const ratio = quota.quota > 0 ? quota.storage / quota.quota : 0;
  // au moins 1 % dès qu'un octet est stocké, pour que la barre ne paraisse pas vide
  const percent = quota.quota > 0 ? Math.max(quota.storage > 0 ? 1 : 0, Math.round(ratio * 100)) : 0;
  const over = ratio >= 1;
  return (
    <div data-workspace-quota className="card card-body gap-8 mt-24 p-16">
      <div className="d-flex align-items-baseline justify-content-between gap-8">
        <strong>{t("workspace.react.quota")}</strong>
        <span className={`fw-bold ${over ? "text-danger" : ratio >= WARN_RATIO ? "text-warning" : "text-gray-700"}`} data-quota-percent>
          {percent} %
        </span>
      </div>
      <div
        className="progress w-100"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.min(100, percent)}
        aria-label={t("workspace.react.quota")}
      >
        <div
          className={`progress-bar ${over ? "bg-danger" : ratio >= WARN_RATIO ? "bg-warning" : "bg-success"}`}
          style={{ width: `${Math.min(100, percent)}%` }}
        />
      </div>
      <small className="text-gray-700">{t("workspace.react.quota.used", { used: formatSize(quota.storage), total: formatSize(quota.quota) })}</small>
      {onManage && (
        <Button
          type="button"
          color="tertiary"
          variant="ghost"
          size="sm"
          className="align-self-start px-0"
          data-quota-manage
          onClick={onManage}
        >
          {t("workspace.react.quota.manage")}
        </Button>
      )}
    </div>
  );
};
