import { DragEvent as ReactDragEvent, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { fileKind, formatSize, parseDate, SortKey } from "../lib/elements";
import { TreeName, ViewMode, WorkspaceElement } from "../lib/types";

const ICONS: Record<string, string> = {
  folder: "📁",
  image: "🖼️",
  pdf: "📕",
  doc: "📝",
  sheet: "📊",
  slides: "📽️",
  audio: "🎵",
  video: "🎞️",
  text: "📄",
  archive: "🗜️",
  other: "📎",
};

/** Colonnes masquées sur petit écran (voir ResponsiveStyle). */
const COL_CLASS: Record<SortKey, string | undefined> = {
  name: undefined,
  owner: "ws-col-owner",
  modified: "ws-col-date",
  size: "ws-col-size",
};

const iconOf = (e: WorkspaceElement) =>
  e.eType === "folder"
    ? ICONS.folder
    : ICONS[fileKind(e.metadata?.["content-type"])];

const dateOf = (e: WorkspaceElement) =>
  parseDate(e.modified)?.toLocaleDateString("fr-FR") ?? "";

/** Liste des dossiers et documents d'un emplacement, en icônes ou en tableau. Un dossier s'ouvre, un document se télécharge (visionneuse : lot W5). */
export const ElementList = ({
  tree,
  elements,
  view,
  selected = new Set<string>(),
  onToggle,
  onToggleAll,
  onDragStart,
  onDropOnFolder,
  canDropOn,
  sort,
  onSort,
  publishedIds = [],
  searching = false,
  onShowComments,
}: {
  tree: TreeName;
  elements: WorkspaceElement[];
  view: ViewMode;
  selected?: Set<string>;
  /** `range` vrai : Maj + clic, sélection de tout ce qui sépare du dernier élément coché. */
  onToggle?: (id: string, range?: boolean) => void;
  onToggleAll?: () => void;
  /** Glisser-déposer interne : saisie d'un élément (renvoie les données à emporter). */
  onDragStart?: (e: WorkspaceElement, ev: DragEvent) => void;
  /** Dépôt d'éléments sur un dossier de la liste. */
  onDropOnFolder?: (folder: WorkspaceElement, ev: DragEvent) => void;
  /** Le dossier accepte-t-il ce glisser ? (survol) */
  canDropOn?: (folder: WorkspaceElement, ev: DragEvent) => boolean;
  /** Tri courant et choix d'un critère : en vue liste, les en-têtes de colonnes deviennent cliquables. */
  sort?: { key: SortKey; desc: boolean };
  onSort?: (key: SortKey) => void;
  /** Documents publiés sur le portail public (leur état n'est pas dans la liste ordinaire). */
  publishedIds?: string[];
  /** Résultats de recherche : on y signale les éléments partagés. */
  searching?: boolean;
  /** Ouvre les commentaires d'un élément (pastille cliquable). */
  onShowComments?: (e: WorkspaceElement) => void;
}) => {
  const { t } = useTranslation("workspace");
  const { pathname } = useLocation();
  const navigate = useNavigate();

  const [over, setOver] = useState<string | null>(null);
  const dnd = (e: WorkspaceElement) => ({
    draggable: !!onDragStart,
    onDragStart: onDragStart
      ? (ev: ReactDragEvent) => onDragStart(e, ev.nativeEvent)
      : undefined,
    onDragOver:
      e.eType === "folder" && onDropOnFolder
        ? (ev: ReactDragEvent) => {
            if (canDropOn?.(e, ev.nativeEvent)) {
              ev.preventDefault();
              ev.stopPropagation();
              setOver(e._id);
            }
          }
        : undefined,
    onDragLeave: () => setOver(null),
    onDrop:
      e.eType === "folder" && onDropOnFolder
        ? (ev: ReactDragEvent) => {
            setOver(null);
            if (canDropOn?.(e, ev.nativeEvent)) {
              ev.preventDefault();
              ev.stopPropagation();
              onDropOnFolder(e, ev.nativeEvent);
            }
          }
        : undefined,
    "data-drop-target": over === e._id ? "true" : undefined,
  });

  const open = (e: WorkspaceElement, style?: React.CSSProperties) =>
    e.eType === "folder" ? (
      <Link
        to={`/${tree}/${e._id}`}
        data-element-open
        style={style}
        title={e.name}
      >
        {e.name}
      </Link>
    ) : (
      <Link
        to={`/view/${e._id}`}
        state={{
          ids: elements.filter((x) => x.eType === "file").map((x) => x._id),
          from: pathname,
        }}
        data-element-open
        style={style}
        title={e.name}
      >
        {e.name}
      </Link>
    );

  /** Pastille bleue du nombre de commentaires (comme l'ancienne interface) ; un clic ouvre les commentaires. Absente sans commentaire et dans la corbeille. */
  const commentBadge = (e: WorkspaceElement, inline = false) =>
    tree !== "trash" &&
    (e.comments?.length ?? 0) > 0 && (
      <button
        type="button"
        className="badge"
        style={{
          border: 0,
          cursor: onShowComments ? "pointer" : "default",
          ...(inline
            ? {
                background: "#2a9cc8",
                color: "#fff",
                borderRadius: 10,
                padding: "0 7px",
                marginRight: 6,
                fontSize: 11,
                lineHeight: "16px",
              }
            : {}),
        }}
        onClick={(ev) => {
          ev.stopPropagation();
          onShowComments?.(e);
        }}
        aria-label={t("workspace.react.action.comments")}
        title={t("workspace.react.action.comments")}
        data-badge-comments
      >
        {e.comments?.length}
      </button>
    );

  const checkbox = (e: WorkspaceElement) =>
    onToggle && (
      <input
        type="checkbox"
        data-element-select
        checked={selected.has(e._id)}
        onChange={() => undefined}
        onClick={(ev) => onToggle(e._id, ev.shiftKey)}
        aria-label={e.name}
      />
    );

  if (view === "list") {
    return (
      <table className="table w-100" data-workspace-list="list">
        <thead>
          <tr>
            <th>
              {onToggleAll && (
                <input
                  type="checkbox"
                  data-select-all
                  checked={
                    elements.length > 0 && selected.size === elements.length
                  }
                  onChange={onToggleAll}
                  aria-label={t("workspace.react.select.all")}
                />
              )}
            </th>
            {(["name", "owner", "modified", "size"] as const).map((key) => (
              <th
                key={key}
                className={COL_CLASS[key]}
                aria-sort={
                  sort?.key === key
                    ? sort.desc
                      ? "descending"
                      : "ascending"
                    : undefined
                }
              >
                {onSort ? (
                  <button
                    type="button"
                    className="btn btn-link p-0"
                    style={{
                      color: "inherit",
                      font: "inherit",
                      fontWeight: 600,
                    }}
                    onClick={() => onSort(key)}
                    data-sort-column={key}
                  >
                    {t(key)} {sort?.key === key ? (sort.desc ? "↓" : "↑") : ""}
                  </button>
                ) : (
                  t(key)
                )}
              </th>
            ))}
            {tree !== "trash" && (
              <th aria-label={t("workspace.react.comments.column")} />
            )}
          </tr>
        </thead>
        <tbody>
          {elements.map((e) => (
            <tr
              key={e._id}
              data-element={e.eType}
              data-element-id={e._id}
              data-selected={selected.has(e._id)}
              style={
                over === e._id ? { outline: "2px dashed #2a9cc8" } : undefined
              }
              {...dnd(e)}
            >
              <td>{checkbox(e)}</td>
              <td>
                <span aria-hidden="true">{iconOf(e)}</span> {open(e)}
                {searching && (e.shared?.length ?? 0) > 0 && (
                  <span
                    role="img"
                    aria-label={t("workspace.react.badge.shared")}
                    title={t("workspace.react.badge.shared")}
                    data-badge-shared
                  >
                    {" "}
                    🔗
                  </span>
                )}
                {e.eType === "file" &&
                  (e.portalPublication || publishedIds.includes(e._id)) && (
                    <span
                      role="img"
                      aria-label={t("workspace.react.badge.published")}
                      title={t("workspace.react.badge.published")}
                      data-badge-published
                    >
                      {" "}
                      🌐
                    </span>
                  )}
              </td>
              <td className="ws-col-owner">
                {e.owner ? (
                  <a href={`/userbook/annuaire#${e.owner}`} data-owner-link>
                    {e.ownerName}
                  </a>
                ) : (
                  e.ownerName
                )}
              </td>
              <td className="ws-col-date">{dateOf(e)}</td>
              <td className="ws-col-size">
                {e.eType === "file" ? formatSize(e.metadata?.size) : ""}
              </td>
              {tree !== "trash" && <td>{commentBadge(e)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  /** Tuile : grande icône (ou vignette d'image), nom en bandeau ; un clic sur la tuile la sélectionne, le nom ouvre. */
  return (
    <ul
      className="list-unstyled d-flex flex-wrap gap-16"
      data-workspace-list="icons"
    >
      {elements.map((e) => {
        const isSelected = selected.has(e._id);
        const thumb =
          e.eType === "file" &&
          fileKind(e.metadata?.["content-type"]) === "image";
        return (
          <li
            key={e._id}
            data-element={e.eType}
            data-element-id={e._id}
            data-selected={isSelected}
            style={{ width: 132, cursor: "pointer", opacity: 1 }}
            onClick={(ev) => {
              if ((ev.target as HTMLElement).closest("a,input,button")) return;
              // un clic sur un dossier y entre ; Maj/Ctrl + clic le sélectionne (la case à cocher aussi)
              if (
                e.eType === "folder" &&
                !ev.shiftKey &&
                !ev.ctrlKey &&
                !ev.metaKey
              )
                navigate(`/${tree}/${e._id}`);
              else if (onToggle) onToggle(e._id, ev.shiftKey);
            }}
            {...dnd(e)}
          >
            <div
              style={{
                position: "relative",
                background: "#fff",
                borderRadius: 8,
                overflow: "hidden",
                boxShadow: "0 1px 4px rgba(0,0,0,.25)",
                outline:
                  over === e._id
                    ? "2px dashed #2a9cc8"
                    : isSelected
                      ? "3px solid #2a9cc8"
                      : "none",
              }}
            >
              <div style={{ position: "absolute", top: 6, left: 8 }}>
                {checkbox(e)}
              </div>
              <div
                style={{
                  height: 96,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  fontSize: 46,
                }}
                aria-hidden="true"
              >
                {thumb ? (
                  <img
                    src={`/workspace/document/${e._id}?thumbnail=120x120`}
                    alt=""
                    style={{ maxWidth: "100%", maxHeight: 96 }}
                    loading="lazy"
                  />
                ) : (
                  iconOf(e)
                )}
              </div>
              <div
                style={{
                  background: isSelected ? "#2a9cc8" : "#7a7a7a",
                  color: "#fff",
                  padding: "5px 8px",
                  fontSize: 13,
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
              >
                {commentBadge(e, true)}
                {open(e, { color: "#fff" })}
              </div>
            </div>
            <small className="d-block text-muted text-center mt-4">
              {e.eType === "file" ? formatSize(e.metadata?.size) : ""}
            </small>
          </li>
        );
      })}
    </ul>
  );
};
