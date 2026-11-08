import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { formatSize } from "../lib/elements";
import { SyncDocument } from "../lib/nextcloud";
import { treeMove, TreeRow } from "../lib/treeKeys";
import { NextcloudQuota } from "../services/nextcloud.service";
import { useNextcloudList } from "../services/queries";

/** Mêmes classes du socle que l'arborescence de l'espace documentaire (voir `TreeNav`). */
const ROW = "action-container d-flex align-items-center gap-4 px-8 py-4";
const CHEVRON = "tree-btn d-inline-flex align-items-center justify-content-center flex-shrink-0";

/** Chemins à déplier pour montrer un dossier : "Cours/6e" → ["Cours", "Cours/6e"]. */
export const pathChain = (path: string): string[] => {
  const parts = path.split("/").filter(Boolean);
  return parts.map((_, i) => parts.slice(0, i + 1).join("/"));
};

/**
 * Sous-dossiers d'un dossier NextCloud, chargés seulement quand on le déplie (le serveur ne renvoie qu'un niveau à la fois),
 * et ainsi de suite à chaque niveau.
 */
const Branch = ({
  userId,
  parentPath,
  depth,
  open,
  onToggle,
  currentPath,
  highlighted,
}: {
  userId?: string;
  parentPath: string;
  depth: number;
  open: Record<string, boolean>;
  onToggle: (path: string) => void;
  currentPath: string;
  highlighted: boolean;
}) => {
  const list = useNextcloudList(userId, parentPath, false, !!userId);
  const folders = (list.data ?? []).filter((d) => d.isFolder);
  return (
    <ul className="list-unstyled mb-0" role="group">
      {folders.map((f) => (
        <Node
          key={f.path}
          folder={f}
          userId={userId}
          depth={depth}
          open={open}
          onToggle={onToggle}
          currentPath={currentPath}
          highlighted={highlighted}
        />
      ))}
    </ul>
  );
};

const Node = ({
  folder,
  userId,
  depth,
  open,
  onToggle,
  currentPath,
  highlighted,
}: {
  folder: SyncDocument;
  userId?: string;
  depth: number;
  open: Record<string, boolean>;
  onToggle: (path: string) => void;
  currentPath: string;
  highlighted: boolean;
}) => {
  const selected = highlighted && currentPath === folder.path;
  return (
    <li
      role="treeitem"
      aria-level={depth + 1}
      aria-expanded={!!open[folder.path]}
      aria-selected={selected}
    >
      <div className={ROW}>
        <button
          type="button"
          className={CHEVRON}
          aria-label={open[folder.path] ? "−" : "+"}
          onClick={() => onToggle(folder.path)}
          data-tree-toggle
          data-nc-tree-toggle={folder.path}
        >
          {open[folder.path] ? "▾" : "▸"}
        </button>
        <Link
          to={`/nextcloud?path=${encodeURIComponent(folder.path)}`}
          data-nc-nav-folder={folder.path}
          className="text-truncate"
        >
          {folder.name}
        </Link>
      </div>
      {open[folder.path] && (
        <Branch
          userId={userId}
          parentPath={folder.path}
          depth={depth + 1}
          open={open}
          onToggle={onToggle}
          currentPath={currentPath}
          highlighted={highlighted}
        />
      )}
    </li>
  );
};

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
  userId,
}: {
  active: boolean;
  trash: boolean;
  /** Dossiers de la racine (déjà chargés par la page). */
  folders: SyncDocument[];
  currentPath: string;
  quota?: NextcloudQuota;
  onCreateFolder?: () => void;
  /** Compte NextCloud : sans lui, seuls les dossiers de la racine s'affichent. */
  userId?: string;
}) => {
  const { t } = useTranslation("workspace");
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [rootOpen, setRootOpen] = useState(true);
  const toggle = (path: string) => setOpen((o) => ({ ...o, [path]: !o[path] }));
  // on entre dans un dossier : son chemin se déplie d'office dans l'arbre
  const chain = pathChain(currentPath).join("|");
  useEffect(() => {
    const ids = chain ? chain.split("|") : [];
    if (ids.length)
      setOpen((o) =>
        ids.every((id) => o[id])
          ? o
          : { ...o, ...Object.fromEntries(ids.map((id) => [id, true])) },
      );
  }, [chain]);
  /** Flèches, Début, Fin : même navigation au clavier que l'arbre de « Mes documents ». */
  const onKeyDown = (ev: React.KeyboardEvent<HTMLElement>) => {
    const link = (ev.target as HTMLElement).closest<HTMLAnchorElement>(
      "a[data-nc-root], a[data-nc-nav-folder], a[data-nc-trash]",
    );
    if (!link || ev.ctrlKey || ev.metaKey || ev.altKey) return;
    const items = [
      ...ev.currentTarget.querySelectorAll<HTMLElement>('li[role="treeitem"]'),
    ];
    const linkOf = (li: Element) =>
      li.querySelector<HTMLAnchorElement>(":scope > div a, :scope > a");
    const rows: TreeRow[] = items.map((li) => ({
      level: Number(li.getAttribute("aria-level") ?? 1),
      expandable: li.hasAttribute("aria-expanded"),
      expanded: li.getAttribute("aria-expanded") === "true",
    }));
    const index = items.findIndex((li) => linkOf(li) === link);
    const move = treeMove(ev.key, rows, index);
    if (!move) return;
    ev.preventDefault();
    if ("focus" in move) linkOf(items[move.focus])?.focus();
    else
      items[index]
        .querySelector<HTMLButtonElement>(":scope > div [data-tree-toggle]")
        ?.click();
  };
  const percent =
    quota && quota.total > 0
      ? Math.min(
          100,
          Math.max(
            quota.used > 0 ? 1 : 0,
            Math.round((quota.used / quota.total) * 100),
          ),
        )
      : 0;
  return (
    <nav
      aria-label={t("nextcloud.documents")}
      data-nc-nav
      className="treeview mt-24"
      onKeyDown={onKeyDown}
    >
      <ul className="list-unstyled mb-0" role="tree">
        <li
          role="treeitem"
          aria-level={1}
          aria-expanded={rootOpen}
          aria-selected={active && !trash && !currentPath}
        >
          <div className={ROW}>
            <button
              type="button"
              className={CHEVRON}
              aria-label={rootOpen ? "−" : "+"}
              onClick={() => setRootOpen((v) => !v)}
              data-tree-toggle
              data-nc-root-toggle
            >
              {rootOpen ? "▼" : "▸"}
            </button>
            <Link
              to="/nextcloud"
              data-tree-root="nextcloud"
              data-nc-root
              className="text-truncate"
            >
              {t("nextcloud.documents")}
            </Link>
          </div>
          {rootOpen && (
            <ul className="list-unstyled mb-0" role="group">
              {folders.map((f) => (
                <Node
                  key={f.path}
                  folder={f}
                  userId={userId}
                  depth={1}
                  open={open}
                  onToggle={toggle}
                  currentPath={currentPath}
                  highlighted={active && !trash}
                />
              ))}
            </ul>
          )}
        </li>
        <li role="treeitem" aria-level={1} aria-selected={trash} className="border-top pt-8 mt-8">
          <div className={ROW}>
            <span className={CHEVRON} style={{ visibility: "hidden" }} aria-hidden="true" />
            <Link to="/nextcloud-trash" data-nc-trash className="text-truncate">
              {t("nextcloud.static.trashbin")}
            </Link>
          </div>
        </li>
      </ul>
      {onCreateFolder && (
        <button
          type="button"
          className="btn btn-tertiary w-100 mt-16"
          onClick={onCreateFolder}
          data-nc-action="new-folder"
        >
          {t("folder.new")}
        </button>
      )}
      {quota && quota.total > 0 && (
        <div className="card card-body gap-8 mt-16 p-16" data-nc-quota>
          <div className="d-flex align-items-baseline justify-content-between gap-8">
            <strong>{t("nextcloud.quota")}</strong>
            <span className="fw-bold text-gray-700">{percent} %</span>
          </div>
          <div className="progress w-100" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}>
            <div className={`progress-bar ${percent >= 90 ? "bg-danger" : "bg-success"}`} style={{ width: `${percent}%` }} />
          </div>
          <small className="text-gray-700">
            {t("workspace.react.quota.used", { used: formatSize(quota.used), total: formatSize(quota.total) })}
          </small>
        </div>
      )}
    </nav>
  );
};
