import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { formatSize } from "../lib/elements";
import { SyncDocument } from "../lib/nextcloud";
import { NextcloudQuota } from "../services/nextcloud.service";

/**
 * Bloc « Documents synchronisés avec NextCloud » de la colonne de gauche, sous celui de l'espace documentaire :
 * dossiers de la racine, corbeille NextCloud, bouton de création et jauge de l'espace synchronisé.
 */
export const NextcloudNav = ({
  active,
  trash,
  folders,
  currentPath,
  quota,
  onCreateFolder,
}: {
  active: boolean;
  trash: boolean;
  folders: SyncDocument[];
  currentPath: string;
  quota?: NextcloudQuota;
  onCreateFolder?: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const percent = quota && quota.total > 0 ? Math.min(100, Math.max(quota.used > 0 ? 1 : 0, Math.round((quota.used / quota.total) * 100))) : 0;
  const row = { display: "flex", alignItems: "center", padding: "6px 10px" } as const;
  return (
    <nav aria-label={t("nextcloud.documents")} data-nc-nav className="mt-24">
      <ul className="list-unstyled mb-0" style={{ borderLeft: "4px solid #ffc800" }}>
        <li>
          <div style={{ ...row, fontWeight: active && !trash && !currentPath ? 700 : 400 }}>
            <span style={{ width: 22 }} aria-hidden="true">
              ▾
            </span>
            <Link to="/nextcloud" data-tree-root="nextcloud" data-nc-root style={active && !trash && !currentPath ? { color: "#ffb400", fontWeight: 700 } : undefined}>
              {t("nextcloud.documents")}
            </Link>
          </div>
          {(
            <ul className="list-unstyled mb-0">
              {folders.map((f) => (
                <li key={f.path} style={{ ...row, paddingLeft: 32, fontWeight: active && !trash && currentPath === f.path ? 700 : 400 }}>
                  <Link to={`/nextcloud?path=${encodeURIComponent(f.path)}`} data-nc-nav-folder={f.path}>
                    {f.name}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </li>
        <li style={{ ...row, paddingLeft: 32, fontWeight: trash ? 700 : 400 }}>
          <Link to="/nextcloud-trash" data-nc-trash>
            {t("nextcloud.static.trashbin")}
          </Link>
        </li>
      </ul>
      {onCreateFolder && (
        <button type="button" className="btn w-100 mt-16" style={{ background: "#e9ebef", color: "#243242" }} onClick={onCreateFolder} data-nc-action="new-folder">
          {t("folder.new")}
        </button>
      )}
      {quota && quota.total > 0 && (
        <div className="mt-16" data-nc-quota>
          <strong className="d-block mb-4">{t("nextcloud.quota")}</strong>
          <div role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent} style={{ height: 10, background: "#fff", border: "1px solid #ced4da", borderRadius: 6, overflow: "hidden" }}>
            <div style={{ width: `${percent}%`, height: "100%", background: "#2a9d3d" }} />
          </div>
          <small className="d-block text-end mt-4">
            {formatSize(quota.used)} / {formatSize(quota.total)}
          </small>
        </div>
      )}
    </nav>
  );
};
