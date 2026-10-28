import { useTranslation } from "react-i18next";
import { formatSize } from "../lib/elements";
import { Quota } from "../lib/types";

/** Jauge d'espace utilisé : barre remplie et « 824,2 Ko / 100,0 Mo ». */
export const QuotaGauge = ({ quota }: { quota: Quota }) => {
  const { t } = useTranslation("workspace");
  const percent = quota.quota > 0 ? Math.min(100, Math.max(quota.storage > 0 ? 1 : 0, Math.round((quota.storage / quota.quota) * 100))) : 0;
  return (
    <div data-workspace-quota className="mt-24">
      <strong className="d-block mb-4">{t("workspace.react.quota")}</strong>
      <div role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent} aria-label={t("workspace.react.quota")} style={{ height: 10, background: "#fff", border: "1px solid #ced4da", borderRadius: 6, overflow: "hidden" }}>
        <div style={{ width: `${percent}%`, height: "100%", background: percent >= 90 ? "#e13a3a" : "#2a9d3d" }} />
      </div>
      <small className="d-block text-end mt-4">
        {formatSize(quota.storage)} / {formatSize(quota.quota)}
      </small>
    </div>
  );
};
