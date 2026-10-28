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
}: {
  tree: TreeName;
  elements: WorkspaceElement[];
  view: ViewMode;
}) => {
  const { t } = useTranslation("workspace");
  const { search } = useLocation();

  const open = (e: WorkspaceElement) =>
    e.eType === "folder" ? (
      <Link to={{ pathname: `/workspace/${tree}/${e._id}`, search }} data-element-open>
        {e.name}
      </Link>
    ) : (
      <a href={`/workspace/document/${e._id}`} data-element-open>
        {e.name}
      </a>
    );

  if (view === "list") {
    return (
      <table className="table w-100" data-workspace-list="list">
        <thead>
          <tr>
            <th>{t("name")}</th>
            <th>{t("owner")}</th>
            <th>{t("modified")}</th>
            <th>{t("size")}</th>
          </tr>
        </thead>
        <tbody>
          {elements.map((e) => (
            <tr key={e._id} data-element={e.eType} data-element-id={e._id}>
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
        <li key={e._id} data-element={e.eType} data-element-id={e._id} style={{ width: 150 }} className="text-center">
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
