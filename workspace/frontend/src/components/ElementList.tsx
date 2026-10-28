import { Link } from "react-router-dom";
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
}: {
  tree: TreeName;
  elements: WorkspaceElement[];
  view: ViewMode;
  selected?: Set<string>;
  onToggle?: (id: string) => void;
  onToggleAll?: () => void;
}) => {
  const { t } = useTranslation("workspace");

  const open = (e: WorkspaceElement) =>
    e.eType === "folder" ? (
      <Link to={`/${tree}/${e._id}`} data-element-open>
        {e.name}
      </Link>
    ) : (
      <a href={`/workspace/document/${e._id}`} data-element-open>
        {e.name}
      </a>
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
            <tr key={e._id} data-element={e.eType} data-element-id={e._id} data-selected={selected.has(e._id)}>
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

  return (
    <ul className="list-unstyled d-flex flex-wrap gap-16" data-workspace-list="icons">
      {elements.map((e) => (
        <li
          key={e._id}
          data-element={e.eType}
          data-element-id={e._id}
          data-selected={selected.has(e._id)}
          style={{ width: 150, outline: selected.has(e._id) ? "2px solid #2a9cc8" : undefined }}
          className="text-center"
        >
          <div className="text-start">{checkbox(e)}</div>
          <div style={{ fontSize: 48 }} aria-hidden="true">
            {iconOf(e)}
          </div>
          <div className="text-break">{open(e)}</div>
          <small className="d-block text-muted">{e.eType === "file" ? formatSize(e.metadata?.size) : ""}</small>
        </li>
      ))}
    </ul>
  );
};
