import { DragEvent as ReactDragEvent, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { fileKind, formatSize, parseDate } from "../lib/elements";
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

const iconOf = (e: WorkspaceElement) => (e.eType === "folder" ? ICONS.folder : ICONS[fileKind(e.metadata?.["content-type"])]);

const dateOf = (e: WorkspaceElement) => parseDate(e.modified)?.toLocaleDateString("fr-FR") ?? "";

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
}: {
  tree: TreeName;
  elements: WorkspaceElement[];
  view: ViewMode;
  selected?: Set<string>;
  onToggle?: (id: string) => void;
  onToggleAll?: () => void;
  /** Glisser-déposer interne : saisie d'un élément (renvoie les données à emporter). */
  onDragStart?: (e: WorkspaceElement, ev: DragEvent) => void;
  /** Dépôt d'éléments sur un dossier de la liste. */
  onDropOnFolder?: (folder: WorkspaceElement, ev: DragEvent) => void;
  /** Le dossier accepte-t-il ce glisser ? (survol) */
  canDropOn?: (folder: WorkspaceElement, ev: DragEvent) => boolean;
}) => {
  const { t } = useTranslation("workspace");
  const { pathname } = useLocation();

  const [over, setOver] = useState<string | null>(null);
  const dnd = (e: WorkspaceElement) => ({
    draggable: !!onDragStart,
    onDragStart: onDragStart ? (ev: ReactDragEvent) => onDragStart(e, ev.nativeEvent) : undefined,
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
      <Link to={`/${tree}/${e._id}`} data-element-open style={style} title={e.name}>
        {e.name}
      </Link>
    ) : (
      <Link to={`/view/${e._id}`} state={{ ids: elements.filter((x) => x.eType === "file").map((x) => x._id), from: pathname }} data-element-open style={style} title={e.name}>
        {e.name}
      </Link>
    );

  const checkbox = (e: WorkspaceElement) =>
    onToggle && (
      <input
        type="checkbox"
        data-element-select
        checked={selected.has(e._id)}
        onChange={() => onToggle(e._id)}
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
                  checked={elements.length > 0 && selected.size === elements.length}
                  onChange={onToggleAll}
                  aria-label={t("workspace.react.select.all")}
                />
              )}
            </th>
            <th>{t("name")}</th>
            <th>{t("owner")}</th>
            <th>{t("modified")}</th>
            <th>{t("size")}</th>
          </tr>
        </thead>
        <tbody>
          {elements.map((e) => (
            <tr key={e._id} data-element={e.eType} data-element-id={e._id} data-selected={selected.has(e._id)} style={over === e._id ? { outline: "2px dashed #2a9cc8" } : undefined} {...dnd(e)}>
              <td>{checkbox(e)}</td>
              <td>
                <span aria-hidden="true">{iconOf(e)}</span> {open(e)}
              </td>
              <td>{e.ownerName}</td>
              <td>{dateOf(e)}</td>
              <td>{e.eType === "file" ? formatSize(e.metadata?.size) : ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  /** Tuile : grande icône (ou vignette d'image), nom en bandeau ; un clic sur la tuile la sélectionne, le nom ouvre. */
  return (
    <ul className="list-unstyled d-flex flex-wrap gap-16" data-workspace-list="icons">
      {elements.map((e) => {
        const isSelected = selected.has(e._id);
        const thumb = e.eType === "file" && fileKind(e.metadata?.["content-type"]) === "image";
        return (
          <li
            key={e._id}
            data-element={e.eType}
            data-element-id={e._id}
            data-selected={isSelected}
            style={{ width: 132, cursor: "pointer", opacity: 1 }}
            onClick={(ev) => {
              if (onToggle && !(ev.target as HTMLElement).closest("a,input,button")) onToggle(e._id);
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
                outline: over === e._id ? "2px dashed #2a9cc8" : isSelected ? "3px solid #2a9cc8" : "none",
              }}
            >
              <div style={{ position: "absolute", top: 6, left: 8 }}>{checkbox(e)}</div>
              <div style={{ height: 96, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 46 }} aria-hidden="true">
                {thumb ? <img src={`/workspace/document/${e._id}?thumbnail=120x120`} alt="" style={{ maxWidth: "100%", maxHeight: 96 }} loading="lazy" /> : iconOf(e)}
              </div>
              <div style={{ background: isSelected ? "#2a9cc8" : "#7a7a7a", color: "#fff", padding: "5px 8px", fontSize: 13, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {open(e, { color: "#fff" })}
              </div>
            </div>
            <small className="d-block text-muted text-center mt-4">{e.eType === "file" ? formatSize(e.metadata?.size) : ""}</small>
          </li>
        );
      })}
    </ul>
  );
};
