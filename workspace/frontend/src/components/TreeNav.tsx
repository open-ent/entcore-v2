import { CSSProperties, DragEvent, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { childFolders } from "../lib/elements";
import { TREES, TREE_LABEL_KEY, TreeName, WorkspaceElement } from "../lib/types";

const ROW: CSSProperties = { display: "flex", alignItems: "center", padding: "6px 10px" };
const SELECTED: CSSProperties = { fontWeight: 700 };
const ACTIVE_LINK: CSSProperties = { color: "#ffb400", fontWeight: 700 };
const CHEVRON: CSSProperties = { background: "none", border: 0, padding: 0, marginRight: 6, cursor: "pointer", color: "inherit", width: 16, textAlign: "center" };

/** Dépôt d'éléments (glisser-déposer interne) sur un dossier de l'arborescence ; `null` = racine de « Mes documents ». */
export interface DropProps {
  canDrop: (targetId: string | null, ev: globalThis.DragEvent) => boolean;
  onDrop: (targetId: string | null, ev: globalThis.DragEvent) => void;
}

const dropHandlers = (targetId: string | null, drop: DropProps | undefined, over: string | null, setOver: (v: string | null) => void) =>
  drop
    ? {
        onDragOver: (ev: DragEvent) => {
          if (drop.canDrop(targetId, ev.nativeEvent)) {
            ev.preventDefault();
            setOver(targetId ?? "root");
          }
        },
        onDragLeave: () => setOver(null),
        onDrop: (ev: DragEvent) => {
          setOver(null);
          if (drop.canDrop(targetId, ev.nativeEvent)) {
            ev.preventDefault();
            drop.onDrop(targetId, ev.nativeEvent);
          }
        },
        "data-drop-target": over === (targetId ?? "root") ? "true" : undefined,
        style: over === (targetId ?? "root") ? { outline: "2px dashed #2a9cc8" } : undefined,
      }
    : {};

const FolderNodes = ({
  tree,
  folders,
  parentId,
  currentId,
  depth,
  drop,
}: {
  tree: TreeName;
  folders: WorkspaceElement[];
  parentId: string | null;
  currentId?: string;
  depth: number;
  drop?: DropProps;
}) => {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [over, setOver] = useState<string | null>(null);
  const nodes = childFolders(folders, parentId);
  if (nodes.length === 0) return null;
  return (
    <ul className="list-unstyled mb-0" role="group">
      {nodes.map((f) => {
        const hasChildren = childFolders(folders, f._id).length > 0;
        const selected = f._id === currentId;
        return (
          <li key={f._id} role="treeitem" aria-expanded={hasChildren ? !!open[f._id] : undefined} aria-selected={selected}>
            <div {...dropHandlers(f._id, drop, over, setOver)} style={{ ...ROW, paddingLeft: 10 + depth * 18, ...(selected ? SELECTED : {}), ...(over === f._id ? { outline: "2px dashed #2a9cc8" } : {}) }}>
              {hasChildren ? (
                <button type="button" style={CHEVRON} aria-label={open[f._id] ? "−" : "+"} onClick={() => setOpen((o) => ({ ...o, [f._id]: !o[f._id] }))} data-tree-toggle>
                  {open[f._id] ? "▾" : "▸"}
                </button>
              ) : (
                <span style={{ ...CHEVRON, cursor: "default" }} />
              )}
              <Link to={`/${tree}/${f._id}`} data-tree-folder={f._id} style={selected ? ACTIVE_LINK : undefined}>
                {f.name}
              </Link>
            </div>
            {open[f._id] && <FolderNodes tree={tree} folders={folders} parentId={f._id} currentId={currentId} depth={depth + 1} drop={drop} />}
          </li>
        );
      })}
    </ul>
  );
};

/**
 * Colonne de gauche : arbres racines (mes documents, partagés, ajoutés dans les applis, publics, corbeille), dossiers dépliables,
 * bouton « Créer un dossier » et, en pied, ce que l'on passe en paramètre (jauge d'espace).
 */
export const TreeNav = ({
  tree,
  folderId,
  folders,
  highlight = true,
  publicVisible = false,
  onCreateFolder,
  drop,
  children,
}: {
  tree: TreeName;
  folderId?: string;
  folders: WorkspaceElement[];
  /** Faux sur la page NextCloud : l'arborescence reste affichée mais aucun emplacement n'y est sélectionné. */
  highlight?: boolean;
  /** « Documents publics » n'apparaît que s'il contient quelque chose (comme dans l'ancienne interface). */
  publicVisible?: boolean;
  /** Présent seulement là où l'on peut créer un dossier. */
  onCreateFolder?: () => void;
  /** Dépôt d'éléments sur « Mes documents » et ses dossiers (glisser-déposer interne). */
  drop?: DropProps;
  children?: React.ReactNode;
}) => {
  const { t } = useTranslation("workspace");
  const [overRoot, setOverRoot] = useState<string | null>(null);
  return (
    <nav aria-label={t("workspace.title")} data-workspace-tree>
      <ul className="list-unstyled mb-0" role="tree" style={{ borderLeft: "4px solid #ffc800" }}>
        {TREES.filter((n) => n !== "public" || publicVisible || n === tree).map((name) => {
          const current = highlight && name === tree;
          return (
            <li key={name} role="treeitem" aria-selected={current}>
              <div
                {...(name === "owner" ? dropHandlers(null, drop, overRoot, setOverRoot) : {})}
                style={{ ...ROW, ...(current && !folderId ? SELECTED : {}), ...(name === "owner" && overRoot === "root" ? { outline: "2px dashed #2a9cc8" } : {}) }}
              >
                <span style={{ ...CHEVRON, cursor: "default" }} aria-hidden="true">
                  {(name === "owner" || name === "shared") && current ? <span style={{ color: "#ffb400" }}>▼</span> : ""}
                </span>
                <Link to={`/${name}`} data-tree-root={name} style={current && !folderId ? ACTIVE_LINK : undefined}>
                  {t(TREE_LABEL_KEY[name])}
                </Link>
              </div>
              {(name === "owner" || name === "shared") && current && <FolderNodes tree={name} folders={folders} parentId={null} currentId={folderId} depth={1} drop={name === "owner" ? drop : undefined} />}
            </li>
          );
        })}
      </ul>
      {onCreateFolder && (
        <button type="button" className="btn w-100 mt-16" style={{ background: "#e9ebef", color: "#243242" }} onClick={onCreateFolder} data-action="new-folder">
          {t("folder.new")}
        </button>
      )}
      {children}
    </nav>
  );
};
