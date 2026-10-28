import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { childFolders } from "../lib/elements";
import { TREES, TreeName, WorkspaceElement } from "../lib/types";

const FolderNodes = ({
  tree,
  folders,
  parentId,
  currentId,
  depth,
}: {
  tree: TreeName;
  folders: WorkspaceElement[];
  parentId: string | null;
  currentId?: string;
  depth: number;
}) => {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const nodes = childFolders(folders, parentId);
  if (nodes.length === 0) return null;
  return (
    <ul className="list-unstyled ps-12" role="group">
      {nodes.map((f) => {
        const hasChildren = childFolders(folders, f._id).length > 0;
        return (
          <li key={f._id} role="treeitem" aria-expanded={hasChildren ? !!open[f._id] : undefined}>
            {hasChildren && (
              <button
                type="button"
                className="btn btn-link p-0 me-4"
                aria-label={open[f._id] ? "−" : "+"}
                onClick={() => setOpen((o) => ({ ...o, [f._id]: !o[f._id] }))}
              >
                {open[f._id] ? "▾" : "▸"}
              </button>
            )}
            <Link
              to={`/${tree}/${f._id}`}
              data-tree-folder={f._id}
              className={f._id === currentId ? "fw-bold" : undefined}
            >
              {f.name}
            </Link>
            {open[f._id] && (
              <FolderNodes tree={tree} folders={folders} parentId={f._id} currentId={currentId} depth={depth + 1} />
            )}
          </li>
        );
      })}
    </ul>
  );
};

/** Arbres racines (mes documents, partagés, ajoutés dans les applis, publics, corbeille) et leurs dossiers. */
export const TreeNav = ({
  tree,
  folderId,
  folders,
}: {
  tree: TreeName;
  folderId?: string;
  folders: WorkspaceElement[];
}) => {
  const { t } = useTranslation("workspace");
  return (
    <nav aria-label={t("workspace.title")} data-workspace-tree>
      <ul className="list-unstyled" role="tree">
        {TREES.map((name) => (
          <li key={name} role="treeitem" aria-selected={name === tree}>
            <Link
              to={`/${name}`}
              data-tree-root={name}
              className={name === tree && !folderId ? "fw-bold" : undefined}
            >
              {t(`workspace.react.tree.${name}`)}
            </Link>
            {(name === "owner" || name === "shared") && name === tree && (
              <FolderNodes tree={name} folders={folders} parentId={null} currentId={folderId} depth={0} />
            )}
          </li>
        ))}
      </ul>
    </nav>
  );
};
