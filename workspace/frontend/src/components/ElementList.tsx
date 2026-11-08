import { Badge, Card, Checkbox } from "@open-ent/react";
import { DragEvent as ReactDragEvent, useMemo, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { fileKind, FileKind, formatSize, parseDate, SortKey } from "../lib/elements";
import { TreeName, ViewMode, WorkspaceElement } from "../lib/types";
import { FileIcon, KIND_LABEL_KEY } from "./FileIcon";

/** Colonnes masquées sur petit écran (voir WorkspaceStyle). */
const COL_CLASS: Record<SortKey, string | undefined> = {
  name: undefined,
  owner: "ws-col-owner",
  modified: "ws-col-date",
  size: "ws-col-size",
};

const kindOf = (e: WorkspaceElement): FileKind | "folder" =>
  e.eType === "folder" ? "folder" : fileKind(e.metadata?.["content-type"]);

const dateOf = (e: WorkspaceElement) => parseDate(e.modified)?.toLocaleDateString("fr-FR") ?? "";

/** Vignette servie par le serveur : seules les images en ont une. */
const thumbnailOf = (e: WorkspaceElement) =>
  e.eType === "file" && fileKind(e.metadata?.["content-type"]) === "image"
    ? `/workspace/document/${e._id}?thumbnail=240x240`
    : undefined;

/**
 * Liste des dossiers et documents d'un emplacement, en cartes ou en tableau. Un dossier s'ouvre ; un document s'ouvre dans
 * la visionneuse (`onOpenFile`), ou sur sa page quand elle n'est pas fournie.
 */
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
  onOpenFile,
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
  /** Ouvre un document dans la visionneuse en modale, sans quitter la liste. */
  onOpenFile?: (e: WorkspaceElement, siblings: string[]) => void;
}) => {
  const { t } = useTranslation("workspace");
  const { pathname } = useLocation();
  const navigate = useNavigate();

  const [over, setOver] = useState<string | null>(null);
  const fileIds = useMemo(() => elements.filter((e) => e.eType === "file").map((e) => e._id), [elements]);
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

  /**
   * Lien d'ouverture. Un document passe par la visionneuse en modale quand elle est disponible, mais garde son `href`
   * vers `/view/:id` : un Ctrl/Cmd + clic ou un clic milieu ouvre toujours la page dans un nouvel onglet.
   */
  const open = (e: WorkspaceElement, className?: string) =>
    e.eType === "folder" ? (
      <Link to={`/${tree}/${e._id}`} data-element-open className={className} title={e.name}>
        {e.name}
      </Link>
    ) : (
      <Link
        to={`/view/${e._id}`}
        state={{ ids: fileIds, from: pathname }}
        data-element-open
        className={className}
        title={e.name}
        onClick={(ev) => {
          if (!onOpenFile || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.button !== 0) return;
          ev.preventDefault();
          onOpenFile(e, fileIds);
        }}
      >
        {e.name}
      </Link>
    );

  /** Pastille du nombre de commentaires ; un clic les ouvre. Absente sans commentaire et dans la corbeille. */
  const commentBadge = (e: WorkspaceElement) =>
    tree !== "trash" &&
    (e.comments?.length ?? 0) > 0 && (
      <Badge
        variant={{ type: "notification", level: "info" }}
        role="button"
        tabIndex={0}
        onClick={(ev) => {
          ev.stopPropagation();
          onShowComments?.(e);
        }}
        aria-label={t("workspace.react.action.comments")}
        title={t("workspace.react.action.comments")}
        data-badge-comments
      >
        {e.comments?.length}
      </Badge>
    );

  /** Partagé (en recherche) et publié au portail : signalés par une pastille discrète. */
  const stateBadges = (e: WorkspaceElement) => (
    <>
      {searching && (e.shared?.length ?? 0) > 0 && (
        <Badge variant={{ type: "content", level: "info" }} title={t("workspace.react.badge.shared")} data-badge-shared>
          {t("workspace.react.badge.shared")}
        </Badge>
      )}
      {e.eType === "file" && (e.portalPublication || publishedIds.includes(e._id)) && (
        <Badge variant={{ type: "content", level: "success" }} title={t("workspace.react.badge.published")} data-badge-published>
          {t("workspace.react.badge.published")}
        </Badge>
      )}
    </>
  );

  const checkbox = (e: WorkspaceElement) =>
    onToggle && (
      <Checkbox
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
                <Checkbox
                  data-select-all
                  checked={elements.length > 0 && selected.size === elements.length}
                  indeterminate={selected.size > 0 && selected.size < elements.length}
                  onChange={onToggleAll}
                  aria-label={t("workspace.react.select.all")}
                />
              )}
            </th>
            {(["name", "owner", "modified", "size"] as const).map((key) => (
              <th
                key={key}
                className={COL_CLASS[key]}
                aria-sort={sort?.key === key ? (sort.desc ? "descending" : "ascending") : undefined}
              >
                {onSort ? (
                  <button type="button" className="btn btn-tertiary btn-sm p-0 fw-bold" onClick={() => onSort(key)} data-sort-column={key}>
                    {t(key)} {sort?.key === key ? (sort.desc ? "↓" : "↑") : ""}
                  </button>
                ) : (
                  t(key)
                )}
              </th>
            ))}
            {tree !== "trash" && <th aria-label={t("workspace.react.comments.column")} />}
          </tr>
        </thead>
        <tbody>
          {elements.map((e) => (
            <tr
              key={e._id}
              data-element={e.eType}
              data-element-id={e._id}
              data-selected={selected.has(e._id)}
              className={over === e._id ? "ws-drop-target" : undefined}
              {...dnd(e)}
            >
              <td>{checkbox(e)}</td>
              <td>
                <span className="d-inline-flex align-items-center gap-8">
                  <FileIcon kind={kindOf(e)} small />
                  {open(e)}
                  {stateBadges(e)}
                </span>
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
              <td className="ws-col-size">{e.eType === "file" ? formatSize(e.metadata?.size) : ""}</td>
              {tree !== "trash" && <td>{commentBadge(e)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  /** Carte : vignette ou icône de type, case à cocher et pastille de type posées dessus, nom et « Vidéo · 46,9 Mo » dessous. */
  const card = (e: WorkspaceElement) => {
    const isSelected = selected.has(e._id);
    const kind = kindOf(e);
    const thumb = thumbnailOf(e);
    return (
      <li
        key={e._id}
        data-element={e.eType}
        data-element-id={e._id}
        data-selected={isSelected}
        {...dnd(e)}
        onClick={(ev) => {
          if ((ev.target as HTMLElement).closest("a,input,button,[role='button']")) return;
          // un clic sur un dossier y entre, sur un document l'ouvre ; Maj/Ctrl + clic sélectionne (la case aussi)
          if (ev.shiftKey || ev.ctrlKey || ev.metaKey) onToggle?.(e._id, ev.shiftKey);
          else if (e.eType === "folder") navigate(`/${tree}/${e._id}`);
          else if (onOpenFile) onOpenFile(e, fileIds);
          else onToggle?.(e._id, false);
        }}
      >
        {/* `isClickable` poserait un bouton invisible couvrant toute la carte (le « Ouvrir la resource » du
            socle) : il avalerait les clics de la case à cocher, du nom et du glisser-déposer, que nous
            gérons nous-mêmes. */}
        <Card
          isSelectable={false}
          isClickable={false}
          isSelected={isSelected}
          className={`ws-card${over === e._id ? " ws-drop-target" : ""}`}
        >
          <div className="ws-card-check">{checkbox(e)}</div>
          {e.eType === "file" && (
            <div className="ws-card-kind">
              <Badge variant={{ type: "chip" }}>{t(KIND_LABEL_KEY[kind])}</Badge>
            </div>
          )}
          <div className="card-image medium">
            {thumb ? <img src={thumb} alt="" loading="lazy" /> : <FileIcon kind={kind} />}
          </div>
          <Card.Body flexDirection="column" space="8">
            <Card.Title>
              <span className="ws-card-name">{open(e)}</span>
            </Card.Title>
            {e.eType === "file" && (
              <Card.Text>
                <span className="text-gray-700">
                  {t(KIND_LABEL_KEY[kind])}
                  {e.metadata?.size !== undefined ? ` · ${formatSize(e.metadata.size)}` : ""}
                </span>
              </Card.Text>
            )}
            {(commentBadge(e) || searching) && (
              <div className="d-flex flex-wrap gap-4">
                {commentBadge(e)}
                {stateBadges(e)}
              </div>
            )}
          </Card.Body>
        </Card>
      </li>
    );
  };

  /**
   * Dossiers et documents sont présentés en deux sections. `sortElements` place déjà les dossiers en premier : il suffit
   * de couper la liste. Pendant une recherche on ne coupe pas, les résultats viennent de partout.
   */
  const folders = elements.filter((e) => e.eType === "folder");
  const files = elements.filter((e) => e.eType !== "folder");
  const grid = (items: WorkspaceElement[], section?: string) => (
    <ul className="ws-grid mb-16" data-section={section}>
      {items.map(card)}
    </ul>
  );
  const section = (key: "folders" | "files", items: WorkspaceElement[]) => (
    <>
      <h2 className="ws-section-title text-gray-700 mb-8" data-section-title={key}>
        {t(key === "folders" ? "workspace.react.section.folders" : "workspace.react.section.files")}
      </h2>
      {grid(items, key)}
    </>
  );
  const split = !searching && folders.length > 0 && files.length > 0;
  return (
    <div data-workspace-list="icons">
      {split ? (
        <>
          {section("folders", folders)}
          {section("files", files)}
        </>
      ) : (
        grid(elements)
      )}
    </div>
  );
};
