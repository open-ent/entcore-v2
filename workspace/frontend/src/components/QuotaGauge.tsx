import { useTranslation } from "react-i18next";
import { formatSize } from "../lib/elements";
import { Quota } from "../lib/types";

/** Jauge d'espace utilisé : « 824,2 Ko sur 100,0 Mo ». */
export const QuotaGauge = ({ quota }: { quota: Quota }) => {
  const { t } = useTranslation("workspace");
  const percent = quota.quota > 0 ? Math.min(100, Math.round((quota.storage / quota.quota) * 100)) : 0;
  return (
    <div data-workspace-quota className="mt-24">
      <p className="mb-4">{t("workspace.react.quota")}</p>
      <progress className="w-100" value={percent} max={100} aria-label={t("workspace.react.quota")} />
      <small>
        {formatSize(quota.storage)} / {formatSize(quota.quota)}
      </small>
    </div>
  );
};
