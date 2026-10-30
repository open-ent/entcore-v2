import { CSSProperties, DragEvent, useState, useEffect, useMemo } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  breadcrumb,
  childFolders,
  foldersOfTree,
  rootFolders,
} from "../lib/elements";
import { canExpand } from "../lib/lazyFolders";
import { treeMove, TreeRow } from "../lib/treeKeys";
import {
  TREES,
  TREE_LABEL_KEY,
  TreeName,
  WorkspaceElement,
} from "../lib/types";

const ROW: CSSProperties = {
  display: "flex",
  alignItems: "center",
  padding: "6px 10px",
};
const SELECTED: CSSProperties = { fontWeight: 700 };
const ACTIVE_LINK: CSSProperties = { color: "#ffb400", fontWeight: 700 };
const CHEVRON: CSSProperties = {
  background: "none",
  border: 0,
  padding: 0,
  marginRight: 6,
  cursor: "pointer",
  color: "inherit",
  width: 16,
  textAlign: "center",
};

/** Dépôt d'éléments (glisser-déposer interne) sur un dossier de l'arborescence ; `null` = racine de « Mes documents ». */
export interface DropProps {
  canDrop: (targetId: string | null, ev: globalThis.DragEvent) => boolean;
  onDrop: (targetId: string | null, ev: globalThis.DragEvent) => void;
  /** Fichiers du poste lâchés sur un dossier (ou sur « Mes documents » si `targetId` est `null`) : à envoyer dans ce dossier. */
  files?: {
    onDrop: (targetId: string | null, ev: globalThis.DragEvent) => void;
  };
  /** Lâcher sur « Corbeille » : mettre les éléments à la corbeille. */
  trash?: {
    canDrop: (ev: globalThis.DragEvent) => boolean;
    onDrop: (ev: globalThis.DragEvent) => void;
  };
}

/** Glisser venant de l'extérieur de la page (fichiers du poste) : les éléments internes portent leur propre type. */
const isFilesDrag = (ev: DragEvent) => ev.dataTransfer.types.includes("Files");

const dropHandlers = (
  targetId: string | null,
  drop: DropProps | undefined,
  over: string | null,
  setOver: (v: string | null) => void,
) =>
  drop
    ? {
        onDragOver: (ev: DragEvent) => {
          if (
            (drop.files && isFilesDrag(ev)) ||
            drop.canDrop(targetId, ev.nativeEvent)
          ) {
            ev.preventDefault();
            setOver(targetId ?? "root");
          }
        },
        onDragLeave: () => setOver(null),
        onDrop: (ev: DragEvent) => {
          setOver(null);
          if (drop.files && isFilesDrag(ev)) {
            ev.preventDefault();
            ev.stopPropagation();
            drop.files.onDrop(targetId, ev.nativeEvent);
            return;
          }
          if (drop.canDrop(targetId, ev.nativeEvent)) {
            ev.preventDefault();
            drop.onDrop(targetId, ev.nativeEvent);
          }
        },
        "data-drop-target": over === (targetId ?? "root") ? "true" : undefined,
        style:
          over === (targetId ?? "root")
            ? { outline: "2px dashed #2a9cc8" }
            : undefined,
      }
    : {};

/** Mode « lazy » : les sous-dossiers se chargent au dépliage (`onExpand`), `isLoaded` dit si c'est déjà fait. */
export interface LazyProps {
  isLoaded: (folderId: string) => boolean;
  onExpand: (folderId: string) => void;
}

const FolderNodes = ({
  tree,
  folders,
  parentId,
  currentId,
  depth,
  drop,
  lazy,
  open,
  onToggle,
}: {
  tree: TreeName;
  folders: WorkspaceElement[];
  parentId: string | null;
  currentId?: string;
  depth: number;
  drop?: DropProps;
  lazy?: LazyProps;
  /** Dossiers dépliés (id → vrai) et bascule : l'état est tenu par `TreeNav`. */
  open: Record<string, boolean>;
  onToggle: (folderId: string) => void;
}) => {
  const [over, setOver] = useState<string | null>(null);
  // premier niveau : les dossiers dont le parent n'est pas dans l'emplacement (un dossier partagé directement n'a pas son parent visible)
  const nodes =
    parentId === null ? rootFolders(folders) : childFolders(folders, parentId);
  if (nodes.length === 0) return null;
  return (
    <ul className="list-unstyled mb-0" role="group">
      {nodes.map((f) => {
        const hasChildren = canExpand(
          childFolders(folders, f._id).length > 0,
          !!lazy,
          !!lazy?.isLoaded(f._id),
        );
        const selected = f._id === currentId;
        return (
          <li
            key={f._id}
            role="treeitem"
            aria-level={depth + 1}
            aria-expanded={hasChildren ? !!open[f._id] : undefined}
            aria-selected={selected}
          >
            <div
              {...dropHandlers(f._id, drop, over, setOver)}
              style={{
                ...ROW,
                paddingLeft: 10 + depth * 18,
                ...(selected ? SELECTED : {}),
                ...(over === f._id ? { outline: "2px dashed #2a9cc8" } : {}),
              }}
            >
              {hasChildren ? (
                <button
                  type="button"
                  style={CHEVRON}
                  aria-label={open[f._id] ? "−" : "+"}
                  onClick={() => onToggle(f._id)}
                  data-tree-toggle
                >
                  {open[f._id] ? "▾" : "▸"}
                </button>
              ) : (
                <span style={{ ...CHEVRON, cursor: "default" }} />
              )}
              <Link
                to={`/${tree}/${f._id}`}
                data-tree-folder={f._id}
                style={selected ? ACTIVE_LINK : undefined}
              >
                {f.name}
              </Link>
            </div>
            {open[f._id] && (
              <FolderNodes
                tree={tree}
                folders={folders}
                parentId={f._id}
                currentId={currentId}
                depth={depth + 1}
                drop={drop}
                lazy={lazy}
                open={open}
                onToggle={onToggle}
              />
            )}
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
/** Lien porté par une ligne de l'arbre (le premier du bandeau de la ligne, pas ceux des sous-dossiers). */
const linkOf = (li: Element) =>
  li.querySelector<HTMLAnchorElement>(
    ":scope > div a[data-tree-root], :scope > div a[data-tree-folder]",
  );

/** Actions au clavier sur le dossier ayant le focus : F2 renomme, Suppr met à la corbeille. */
export interface TreeKeys {
  onRename: (folderId: string) => void;
  onDelete: (folderId: string) => void;
}

export const TreeNav = ({
  tree,
  folderId,
  folders,
  highlight = true,
  keepOpen = false,
  publicVisible = false,
  onCreateFolder,
  drop,
  keys,
  lazy,
  userId,
  children,
}: {
  tree: TreeName;
  folderId?: string;
  folders: WorkspaceElement[];
  /** Faux sur la page NextCloud : l'arborescence reste affichée mais aucun emplacement n'y est sélectionné. */
  highlight?: boolean;
  /** Vrai sur la page NextCloud : « Documents personnels » garde ses dossiers dépliés, comme sur la page d'accueil de l'espace documentaire. */
  keepOpen?: boolean;
  /** « Documents publics » n'apparaît que s'il contient quelque chose (comme dans l'ancienne interface). */
  publicVisible?: boolean;
  /** Présent seulement là où l'on peut créer un dossier. */
  onCreateFolder?: () => void;
  /** Dépôt d'éléments sur « Mes documents » et ses dossiers (glisser-déposer interne). */
  drop?: DropProps;
  /** F2 et Suppr sur un dossier de l'arbre (absent là où on ne peut pas renommer ni supprimer). */
  keys?: TreeKeys;
  /** Mode « lazy » de la plateforme : dossiers chargés au dépliage. */
  lazy?: LazyProps;
  /** Usager connecté : sépare « Documents personnels » (ses dossiers non partagés) de « Documents partagés ». */
  userId?: string;
  children?: React.ReactNode;
}) => {
  const { t } = useTranslation("workspace");
  const [overRoot, setOverRoot] = useState<string | null>(null);
  // Dossiers dépliés, et emplacements racines pliés/dépliés à la main (sinon : déplié quand c'est l'emplacement courant)
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [rootOpen, setRootOpen] = useState<Partial<Record<TreeName, boolean>>>(
    {},
  );
  const toggle = (id: string) => {
    if (!open[id]) lazy?.onExpand(id);
    setOpen((o) => ({ ...o, [id]: !o[id] }));
  };
  // On entre dans un dossier (depuis la liste, un lien, la barre d'adresse) : son chemin se déplie d'office dans l'arbre,
  // en mode « lazy » avec les sous-dossiers de chaque étape.
  const chain = useMemo(
    () => breadcrumb(folders, folderId ?? null).map((f) => f._id),
    [folders, folderId],
  );
  const chainKey = chain.join("|");
  useEffect(() => {
    if (chain.length === 0) return;
    chain.forEach((id) => lazy?.onExpand(id));
    setOpen((o) =>
      chain.every((id) => o[id])
        ? o
        : { ...o, ...Object.fromEntries(chain.map((id) => [id, true])) },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chainKey]);
  const trashHandlers = (over: boolean) =>
    drop?.trash
      ? {
          onDragOver: (ev: DragEvent) => {
            if (drop.trash?.canDrop(ev.nativeEvent)) {
              ev.preventDefault();
              setOverRoot("trash");
            }
          },
          onDragLeave: () => setOverRoot(null),
          onDrop: (ev: DragEvent) => {
            setOverRoot(null);
            if (drop.trash?.canDrop(ev.nativeEvent)) {
              ev.preventDefault();
              drop.trash.onDrop(ev.nativeEvent);
            }
          },
          "data-drop-target": over ? "true" : undefined,
        }
      : {};
  /** Flèches, Début, Fin (navigation) ; F2 et Suppr (renommer, supprimer) sur le dossier ayant le focus. */
  const onKeyDown = (ev: React.KeyboardEvent<HTMLElement>) => {
    const link = (ev.target as HTMLElement).closest<HTMLAnchorElement>(
      "a[data-tree-root], a[data-tree-folder]",
    );
    if (!link || ev.ctrlKey || ev.metaKey || ev.altKey) return;
    const folder = link.dataset.treeFolder;
    if (folder && keys && (ev.key === "F2" || ev.key === "Delete")) {
      ev.preventDefault();
      (ev.key === "F2" ? keys.onRename : keys.onDelete)(folder);
      return;
    }
    const items = [
      ...ev.currentTarget.querySelectorAll<HTMLElement>('li[role="treeitem"]'),
    ];
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
  return (
    <nav
      aria-label={t("workspace.title")}
      data-workspace-tree
      onKeyDown={onKeyDown}
    >
      <ul
        className="list-unstyled mb-0"
        role="tree"
        style={{ borderLeft: "4px solid #ffc800" }}
      >
        {TREES.filter((n) => n !== "public" || publicVisible || n === tree).map(
          (name) => {
            const current = highlight && name === tree;
            const expandable = name === "owner" || name === "shared";
            const expanded =
              rootOpen[name] ?? (current || (keepOpen && name === tree));
            return (
              <li
                key={name}
                role="treeitem"
                aria-level={1}
                aria-expanded={expandable ? expanded : undefined}
                aria-selected={current}
              >
                <div
                  {...(name === "owner"
                    ? dropHandlers(null, drop, overRoot, setOverRoot)
                    : name === "trash"
                      ? trashHandlers(overRoot === "trash")
                      : {})}
                  style={{
                    ...ROW,
                    ...(current && !folderId ? SELECTED : {}),
                    ...((name === "owner" && overRoot === "root") ||
                    (name === "trash" && overRoot === "trash")
                      ? { outline: "2px dashed #2a9cc8" }
                      : {}),
                  }}
                >
                  {expandable ? (
                    <button
                      type="button"
                      style={{ ...CHEVRON, color: "#ffb400" }}
                      aria-label={expanded ? "−" : "+"}
                      onClick={() =>
                        setRootOpen((r) => ({ ...r, [name]: !expanded }))
                      }
                      data-tree-toggle
                      data-tree-root-toggle={name}
                    >
                      {expanded ? "▼" : "▸"}
                    </button>
                  ) : (
                    <span
                      style={{ ...CHEVRON, cursor: "default" }}
                      aria-hidden="true"
                    />
                  )}
                  <Link
                    to={`/${name}`}
                    data-tree-root={name}
                    style={current && !folderId ? ACTIVE_LINK : undefined}
                  >
                    {t(TREE_LABEL_KEY[name])}
                  </Link>
                </div>
                {(name === "owner" || name === "shared") && expanded && (
                  <FolderNodes
                    tree={name}
                    folders={foldersOfTree(
                      name as "owner" | "shared",
                      folders,
                      userId,
                    )}
                    parentId={null}
                    currentId={folderId}
                    depth={1}
                    drop={name === "owner" ? drop : undefined}
                    lazy={lazy}
                    open={open}
                    onToggle={toggle}
                  />
                )}
              </li>
            );
          },
        )}
      </ul>
      {onCreateFolder && (
        <button
          type="button"
          className="btn w-100 mt-16"
          style={{ background: "#e9ebef", color: "#243242" }}
          onClick={onCreateFolder}
          data-action="new-folder"
        >
          {t("folder.new")}
        </button>
      )}
      {children}
    </nav>
  );
};
