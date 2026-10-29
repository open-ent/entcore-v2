import {
  AppHeader,
  LoadingScreen,
  SegmentedControl,
  ShareModal,
  useEdificeClient,
} from "@open-ent/react";
import { useQueryClient } from "@tanstack/react-query";
import { DragEvent, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, Navigate, useLocation, useNavigate, useParams } from "react-router-dom";
import { ActionBar } from "../components/ActionBar";
import { CommentsModal } from "../components/CommentsModal";
import { ConfirmModal } from "../components/ConfirmModal";
import { LoolCreateModal } from "../components/LoolCreateModal";
import { NextcloudNav } from "../components/NextcloudNav";
import { NextcloudPicker } from "../components/NextcloudPicker";
import { ElementList } from "../components/ElementList";
import { FolderPicker } from "../components/FolderPicker";
import { PublishModal } from "../components/PublishModal";
import { RenameModal } from "../components/RenameModal";
import { ShareWarning } from "../components/ShareWarning";
import { VersionsModal } from "../components/VersionsModal";
import { NewFolderModal } from "../components/NewFolderModal";
import { UploadPanel } from "../components/UploadPanel";
import { ZipDialog } from "../components/ZipDialog";
import { LoadMore, PAGE_SIZE } from "../components/LoadMore";
import { QuotaGauge } from "../components/QuotaGauge";
import { TreeNav } from "../components/TreeNav";
import {
  ActionId,
  availableActions,
  downloadUrl,
  forbiddenTargets,
} from "../lib/actions";
import { breadcrumb, isTreeName, sortElements, SortKey } from "../lib/elements";
import { nextSort, SORT_FIELD, sortKeyOf, viewOf } from "../lib/preferences";
import { isTypingTarget, rangeIds, shortcutOf } from "../lib/selection";
import { readFlags } from "../lib/flags";
import {
  canOpenInGeogebra,
  canOpenInLool,
  canOpenInScratch,
  geogebraUrl,
  LOOL_CREATE,
  LOOL_OPEN,
  loolUrl,
  scratchUrl,
} from "../lib/integrations";
import { NEXTCLOUD_VIEW } from "../lib/nextcloud";
import { rightsOn } from "../lib/rights";
import {
  cloudDriveAvailable,
  downloadFromDrive,
  openPicker,
  requestAccessToken,
  uploadToDrive,
} from "../lib/cloudDrive";
import {
  canDropOn,
  DRAG_TYPE,
  draggedElements,
  encodeDrag,
  isInternalDrag,
} from "../lib/dnd";
import { clientFilter, locationOf, searchQuery } from "../lib/search";
import { isZip, planUpload, remainingLabel } from "../lib/upload";
import { TREE_LABEL_KEY, ViewMode, WorkspaceElement } from "../lib/types";
import { useUploads } from "../services/useUploads";
import { nextcloudService } from "../services/nextcloud.service";
import {
  elementActions,
  workspaceService,
} from "../services/workspace.service";
import {
  useContent,
  useFolders,
  useLoolContext,
  useNextcloudList,
  useNextcloudQuota,
  usePublishedIds,
  useQuota,
  useSearch,
  useWorkspacePreference,
} from "../services/queries";

/** Navigation dans l'espace documentaire : arbres, dossiers, fil d'Ariane, liste en icônes ou en tableau, jauge d'espace. */
export const Browser = () => {
  const { t } = useTranslation("workspace");
  const { tree, folderId } = useParams();
  const location = useLocation();
  const [view, setView] = useState<ViewMode>("icons");
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({
    key: "name",
    desc: false,
  });

  // Affichage et tri mémorisés dans la préférence « workspace » de l'usager (la même que celle de l'ancienne interface)
  const preference = useWorkspacePreference();
  const preferenceApplied = useRef(false);
  useEffect(() => {
    if (!preference.data || preferenceApplied.current) return;
    preferenceApplied.current = true;
    setView(viewOf(preference.data));
    const key = sortKeyOf(preference.data.sortField);
    if (key) setSort({ key, desc: preference.data.sortDesc === true });
  }, [preference.data]);
  const changeView = (v: ViewMode) => {
    setView(v);
    void workspaceService.savePreference({ view: v });
  };
  const changeSort = (key: SortKey, toggle = true) => {
    const next = toggle ? nextSort(sort, key) : { key, desc: false };
    setSort(next);
    void workspaceService.savePreference({
      sortField: SORT_FIELD[next.key],
      sortDesc: next.desc,
    });
  };

  const validTree = isTreeName(tree) ? tree : "owner";
  const content = useContent(validTree, folderId);
  const folders = useFolders();
  const publicRoot = useContent("public");
  const { user } = useEdificeClient();
  const quota = useQuota(user?.userId);

  // Création et import : dans « Mes documents » et ses dossiers (le partage au dépôt arrive avec le lot W4).
  const groupIds =
    (user as { groupsIds?: string[] } | undefined)?.groupsIds ?? [];
  const functions = ((
    user as { functions?: Record<string, unknown> } | undefined
  )?.functions ?? {}) as Record<string, unknown>;
  const canPublishToPortal = !!(
    functions["ADMIN_LOCAL"] || functions["SUPER_ADMIN"]
  );
  const flags = useMemo(() => readFlags(), []);
  const workflows = new Set(
    (
      (user as { authorizedActions?: Array<{ name: string }> } | undefined)
        ?.authorizedActions ?? []
    ).map((a) => a.name),
  );
  const canOpenOnline = flags.enableLool && workflows.has(LOOL_OPEN);
  const driveAvailable = cloudDriveAvailable(flags);
  const ncAvailable = flags.enableNextcloud && workflows.has(NEXTCLOUD_VIEW);
  const canCreateOnline = flags.enableLool && workflows.has(LOOL_CREATE);
  const lool = useLoolContext(canOpenOnline);
  const ncRoot = useNextcloudList(user?.userId, "", false, ncAvailable);
  const ncQuota = useNextcloudQuota(user?.userId, ncAvailable);
  // Établissements dont l'usager est administrateur local (portée de sa fonction), puis ceux auxquels il est rattaché.
  const adminScope = (
    (functions["ADMIN_LOCAL"] as { scope?: string[] } | undefined)?.scope ?? []
  ).concat(
    (functions["SUPER_ADMIN"] as { scope?: string[] } | undefined)?.scope ?? [],
  );
  const structureIds = [
    ...new Set([
      ...adminScope,
      ...((user as { structures?: string[] } | undefined)?.structures ?? []),
    ]),
  ];
  const published = usePublishedIds(structureIds, canPublishToPortal);
  const currentFolder = (folders.data ?? []).find((f) => f._id === folderId);
  // « Mes documents » et ses dossiers ; dans « Documents partagés », seulement un dossier où l'on a le droit de contribuer.
  const canWrite =
    validTree === "owner" ||
    (validTree === "shared" &&
      !!currentFolder &&
      rightsOn(currentFolder, user?.userId, groupIds).contrib);
  const uploads = useUploads(folderId);
  const [newFolderOpen, setNewFolderOpen] = useState(false);
  const [pendingZip, setPendingZip] = useState<File | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  // Recherche : critère saisi, recherche active (dans le dossier courant ou dans tout l'espace)
  const navigate = useNavigate();
  const [criteria, setCriteria] = useState("");
  const [query, setQuery] = useState<{
    criteria: string;
    everywhere: boolean;
  } | null>(null);
  useEffect(() => {
    setQuery(null);
    setCriteria("");
  }, [tree, folderId]);
  const searchQs = query
    ? searchQuery({ ...query, tree: validTree, folderId })
    : undefined;
  const search = useSearch(flags.disableFullTextSearch ? undefined : searchQs);
  // Sans index texte (réglage serveur) ou si le serveur le refuse : filtre sur les noms des éléments déjà chargés.
  const searchDegraded =
    !!query && (flags.disableFullTextSearch || search.isError);
  const baseElements = !query
    ? (content.data ?? [])
    : searchDegraded
      ? clientFilter(content.data ?? [], query.criteria)
      : (search.data ?? []);
  const elements = useMemo(
    () => sortElements(baseElements, sort.key, sort.desc),
    [baseElements, sort],
  );

  // Sélection et actions de gestion
  const qc = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [picker, setPicker] = useState<"copy" | "move" | null>(null);
  const [renaming, setRenaming] = useState<WorkspaceElement | null>(null);
  const [renameError, setRenameError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<
    "delete" | "empty" | "trashShared" | null
  >(null);
  const [warnShare, setWarnShare] = useState<WorkspaceElement | null>(null);
  const [sharing, setSharing] = useState<WorkspaceElement | null>(null);
  // Éléments partagés ensemble : la fenêtre règle le premier, ses droits sont ensuite recopiés sur les autres
  const [shareSet, setShareSet] = useState<WorkspaceElement[]>([]);
  const [copyingForShare, setCopyingForShare] = useState(false);
  const [publishing, setPublishing] = useState<WorkspaceElement | null>(null);
  const [versions, setVersions] = useState<WorkspaceElement | null>(null);
  const [commenting, setCommenting] = useState<WorkspaceElement | null>(null);
  const [loolCreate, setLoolCreate] = useState(false);
  const dragged = useRef<WorkspaceElement[]>([]);
  const [ncPicker, setNcPicker] = useState<"copy" | "move" | null>(null);
  useEffect(() => setSelected(new Set()), [tree, folderId]);
  // Liste affichée par tranches de 50 : on repart du début à chaque changement d'emplacement, de tri ou de recherche
  const [shown, setShown] = useState(PAGE_SIZE);
  useEffect(() => setShown(PAGE_SIZE), [tree, folderId, sort, query]);
  const visible = useMemo(() => elements.slice(0, shown), [elements, shown]);

  const selection = useMemo(
    () => elements.filter((e) => selected.has(e._id)),
    [elements, selected],
  );
  const actions = availableActions(validTree, selection, {
    canManage:
      selection.length === 1 &&
      rightsOn(selection[0], user?.userId, groupIds).manager,
    managerOnAll: selection.every(
      (e) => rightsOn(e, user?.userId, groupIds).manager,
    ),
    canPublish: canPublishToPortal,
    searching: !!query,
    nextcloud: ncAvailable,
    cloudDrive: driveAvailable,
    integration: {
      lool:
        canOpenOnline &&
        selection.length === 1 &&
        selection[0].eType === "file" &&
        canOpenInLool(lool.data, selection[0]),
      scratch:
        flags.enableScratch &&
        selection.length === 1 &&
        canOpenInScratch(selection[0]),
      geogebra:
        flags.enableGeogebra &&
        selection.length === 1 &&
        canOpenInGeogebra(selection[0]),
    },
  });
  // Dernier élément coché : point de départ d'un Maj + clic
  const lastToggled = useRef<string | undefined>(undefined);
  const toggle = (id: string, range = false) => {
    const wanted = range
      ? rangeIds(
          visible.map((e) => e._id),
          lastToggled.current,
          id,
        )
      : [];
    setSelected((cur) => {
      const next = new Set(cur);
      if (wanted.length) wanted.forEach((w) => next.add(w));
      else if (!next.delete(id)) next.add(id);
      return next;
    });
    lastToggled.current = id;
  };
  const toggleAll = () =>
    setSelected((cur) =>
      cur.size === visible.length
        ? new Set()
        : new Set(visible.map((e) => e._id)),
    );

  /** Exécute une action serveur, rafraîchit la liste, l'arborescence et la jauge ; un échec est signalé. */
  const run = async (fn: () => Promise<unknown>) => {
    setNotice(null);
    try {
      await fn();
      setSelected(new Set());
      await Promise.all(
        ["content", "folders", "quota"].map((k) =>
          qc.invalidateQueries({ queryKey: ["workspace", k] }),
        ),
      );
      return true;
    } catch {
      setNotice(t("workspace.react.action.failed"));
      return false;
    }
  };

  // Glisser-déposer interne : saisir un élément (ou la sélection) et le lâcher sur un dossier pour l'y déplacer
  const onDragStart = (el: WorkspaceElement, ev: globalThis.DragEvent) => {
    const items = draggedElements(el, selection);
    dragged.current = items;
    ev.dataTransfer?.setData(DRAG_TYPE, encodeDrag(items));
    if (ev.dataTransfer) ev.dataTransfer.effectAllowed = "move";
  };
  const canDrop = (targetId: string | null, ev: globalThis.DragEvent) =>
    isInternalDrag([...(ev.dataTransfer?.types ?? [])]) &&
    canDropOn(targetId, dragged.current, folders.data ?? []);
  const moveDragged = (targetId: string | null) => {
    const items = dragged.current;
    dragged.current = [];
    if (items.length)
      void run(() => elementActions.move(items, targetId ?? undefined));
  };

  const onAction = (a: ActionId) => {
    if (a === "download") window.location.assign(downloadUrl(selection));
    // dans « Documents partagés », mettre à la corbeille supprime aussi pour les autres personnes qui voient ces éléments : on confirme
    else if (a === "trash")
      validTree === "shared"
        ? setConfirm("trashShared")
        : void run(() => elementActions.trash(selection));
    else if (a === "restore") void run(() => elementActions.restore(selection));
    else if (a === "delete") setConfirm("delete");
    else if (a === "share") {
      setShareSet(selection);
      (validTree === "owner" ? setWarnShare : setSharing)(selection[0]);
    }
    else if (a === "publish") setPublishing(selection[0]);
    else if (a === "history") setVersions(selection[0]);
    else if (a === "comments") setCommenting(selection[0]);
    else if (a === "location") navigate(locationOf(selection[0]));
    else if (a === "driveExport") void exportToDrive();
    else if (a === "ncCopy") setNcPicker("copy");
    else if (a === "ncMove") setNcPicker("move");
    else if (a === "editImage") navigate(`/view/${selection[0]._id}?edit=image`, { state: { ids: [selection[0]._id], from: location.pathname } });
    else if (a === "openLool") window.open(loolUrl(selection[0]._id));
    else if (a === "openScratch") window.open(scratchUrl(selection[0]._id));
    else if (a === "openGeogebra") window.open(geogebraUrl(selection[0]));
    else if (a === "rename") {
      setRenameError(null);
      setRenaming(selection[0]);
    } else if (a === "copy" || a === "move") setPicker(a);
  };

  // Raccourcis de l'ancienne interface : Ctrl+A tout sélectionner, Ctrl+C copier, Ctrl+X déplacer (jamais dans un champ de saisie,
  // ni quand une fenêtre est ouverte, ni quand du texte est sélectionné pour être copié)
  const shortcut = useRef<(ev: KeyboardEvent) => void>(() => undefined);
  shortcut.current = (ev) => {
    const which = shortcutOf(ev);
    if (
      !which ||
      isTypingTarget(ev.target) ||
      document.querySelector('[role="dialog"]')
    )
      return;
    if (which === "select-all") {
      ev.preventDefault();
      toggleAll();
    } else if (!window.getSelection()?.toString() && actions.includes(which)) {
      ev.preventDefault();
      setPicker(which);
    }
  };
  useEffect(() => {
    const listener = (ev: KeyboardEvent) => shortcut.current(ev);
    document.addEventListener("keydown", listener);
    return () => document.removeEventListener("keydown", listener);
  }, []);

  // Lecteur externe (Google Drive) : le consentement se demande dans le navigateur, aucun jeton ne passe par l'ENT.
  const [driveBusy, setDriveBusy] = useState(false);
  const importFromDrive = async () => {
    if (driveBusy) return;
    setNotice(null);
    try {
      const token = await requestAccessToken(flags.cloudDriveGoogleClientId);
      const picked = await openPicker(token, "files", flags);
      if (!picked.length) return;
      setDriveBusy(true);
      const files: File[] = [];
      for (const p of picked) {
        try {
          files.push(await downloadFromDrive(p, token));
        } catch {
          setNotice(`${t("workspace.cloud.drive.import.error.one")} ${p.name}`);
        }
      }
      if (files.length) {
        await uploads.send(files);
        setNotice((n) => n ?? t("workspace.cloud.drive.import.success"));
      }
    } catch {
      setNotice(t("workspace.cloud.drive.error"));
    } finally {
      setDriveBusy(false);
    }
  };
  const exportToDrive = async () => {
    if (driveBusy) return;
    setNotice(null);
    const files = selection.filter((e) => e.eType === "file");
    try {
      const token = await requestAccessToken(flags.cloudDriveGoogleClientId);
      const folders = await openPicker(token, "folder", flags);
      setDriveBusy(true);
      let failed = "";
      for (const f of files) {
        try {
          await uploadToDrive(f._id, f.name, token, folders[0]?.id);
        } catch {
          failed = `${t("workspace.cloud.drive.export.error.one")} ${f.name}`;
        }
      }
      setNotice(failed || t("workspace.cloud.drive.export.success"));
    } catch {
      setNotice(t("workspace.cloud.drive.error"));
    } finally {
      setDriveBusy(false);
    }
  };

  const importFiles = (list: File[]) => {
    setNotice(null);
    const maxSize = parseInt(t("max.file.size"), 10) || 0;
    const plan = planUpload(list, quota.data, maxSize);
    const messages = new Set<string>();
    for (const r of plan.rejected) {
      messages.add(
        r.reason === "too-large"
          ? `${t("file.too.large.limit")}${Math.round(maxSize / 1024 / 1024)} ${t("mb")}`
          : `${t("file.too.large.max")}${remainingLabel(quota.data)}`,
      );
    }
    if (messages.size) setNotice([...messages].join(" "));
    const zips = plan.accepted.filter(isZip);
    const others = plan.accepted.filter((f) => !isZip(f));
    if (others.length) void uploads.send(others);
    if (zips.length) setPendingZip(zips[0]);
  };

  const onDrop = (e: DragEvent) => {
    if (isInternalDrag([...e.dataTransfer.types])) return;
    e.preventDefault();
    setDragging(false);
    if (!canWrite) return;
    const items = [...e.dataTransfer.items];
    // Un dossier déposé ne peut pas être importé : seuls les fichiers le sont (comme dans l'ancienne interface).
    if (items.some((i) => i.webkitGetAsEntry?.()?.isDirectory)) {
      setNotice(
        `${t("workspace.import.warning")}. ${t("workspace.import.solution").replace(/<[^>]+>/g, "")}`,
      );
      return;
    }
    importFiles([...e.dataTransfer.files]);
  };

  const path = useMemo(
    () => breadcrumb(folders.data ?? [], folderId ?? null),
    [folders.data, folderId],
  );

  if (!isTreeName(tree)) return <Navigate to="/owner" replace />;

  return (
    <>
      <AppHeader>
        <h1 className="h2" data-workspace-title>
          {t("workspace.title")}
        </h1>
      </AppHeader>
      <div className="d-flex gap-24 flex-wrap">
        <aside style={{ flex: "0 0 260px", maxWidth: "100%" }}>
          <TreeNav
            tree={validTree}
            folderId={folderId}
            folders={folders.data ?? []}
            publicVisible={(publicRoot.data ?? []).length > 0}
            onCreateFolder={canWrite ? () => setNewFolderOpen(true) : undefined}
            drop={
              validTree === "owner"
                ? { canDrop, onDrop: (id) => moveDragged(id) }
                : undefined
            }
          >
            {quota.data && <QuotaGauge quota={quota.data} />}
          </TreeNav>
          {ncAvailable && (
            <NextcloudNav
              active={false}
              trash={false}
              folders={(ncRoot.data ?? []).filter((d) => d.isFolder)}
              currentPath=""
              quota={ncQuota.data}
            />
          )}
        </aside>
        <main
          style={{
            flex: "1 1 320px",
            minWidth: 0,
            outline: dragging && canWrite ? "2px dashed #2a9cc8" : undefined,
          }}
          data-workspace-dropzone
          onDragOver={(e) => {
            if (isInternalDrag([...e.dataTransfer.types])) return;
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
        >
          <nav
            aria-label={t("workspace.react.breadcrumb")}
            data-workspace-breadcrumb
            className="mb-12"
          >
            <Link to={`/${validTree}`}>{t(TREE_LABEL_KEY[validTree])}</Link>
            {path.map((f) => (
              <span key={f._id}>
                {" / "}
                <Link to={`/${validTree}/${f._id}`}>{f.name}</Link>
              </span>
            ))}
          </nav>
          <div
            className="d-flex flex-wrap gap-16 align-items-center justify-content-between mb-16"
            data-workspace-header
          >
            {canWrite && (
              <div className="d-flex flex-wrap gap-12" data-workspace-toolbar>
                <button
                  type="button"
                  className="btn btn-outline-primary"
                  onClick={() => fileInput.current?.click()}
                  data-action="import"
                >
                  {t("import.file")}
                </button>
                {canWrite && driveAvailable && (
                  <button
                    type="button"
                    className="btn btn-outline-primary"
                    disabled={driveBusy}
                    onClick={() => void importFromDrive()}
                    data-action="drive-import"
                  >
                    {t("workspace.cloud.drive.import.action")}
                  </button>
                )}
                <button
                  type="button"
                  className="btn btn-outline-primary"
                  onClick={() => setNewFolderOpen(true)}
                  data-toolbar-new-folder
                >
                  {t("folder.new")}
                </button>
                {canCreateOnline && (
                  <button
                    type="button"
                    className="btn btn-outline-primary"
                    onClick={() => setLoolCreate(true)}
                    data-action="lool-create"
                  >
                    {t("workspace.lool.create-document")}
                  </button>
                )}
                <input
                  ref={fileInput}
                  type="file"
                  multiple
                  hidden
                  data-import-input
                  onChange={(e) => {
                    importFiles([...(e.target.files ?? [])]);
                    e.target.value = "";
                  }}
                />
              </div>
            )}
            <form
              className="d-flex gap-8 align-items-center"
              role="search"
              data-workspace-search
              onSubmit={(e) => {
                e.preventDefault();
                if (criteria.trim())
                  setQuery({ criteria: criteria.trim(), everywhere: false });
              }}
            >
              <input
                type="search"
                className="form-control"
                style={{ maxWidth: 360 }}
                value={criteria}
                placeholder={t("workspace.search.placeholder")}
                aria-label={t("workspace.search.placeholder")}
                onChange={(e) => setCriteria(e.target.value)}
                data-search-input
              />
              <button
                type="submit"
                className="btn btn-outline-primary"
                data-search-submit
              >
                {t("workspace.react.search")}
              </button>
              {query && (
                <button
                  type="button"
                  className="btn btn-outline-secondary btn-sm"
                  data-search-reset
                  onClick={() => {
                    setQuery(null);
                    setCriteria("");
                  }}
                >
                  {t("workspace.react.search.reset")}
                </button>
              )}
            </form>
          </div>
          {query && (
            <div className="mb-16" data-search-banner>
              <strong>
                {query.everywhere
                  ? t("workspace.search.results.inworkspace")
                  : `${t("workspace.search.results.infolder")} ${path.length ? path[path.length - 1].name : t(TREE_LABEL_KEY[validTree])}`}
              </strong>
              {!query.everywhere && (
                <button
                  type="button"
                  className="btn btn-outline-secondary btn-sm"
                  data-search-everywhere
                  onClick={() => setQuery({ ...query, everywhere: true })}
                >
                  {t("workspace.search.inworkspace")}
                </button>
              )}
              {search.isFetching && !searchDegraded && (
                <span> — {t("workspace.search.inprogress")}</span>
              )}
              {searchDegraded && !flags.disableFullTextSearch && (
                <p
                  className="text-warning mb-0"
                  role="status"
                  data-search-degraded
                >
                  {t("workspace.react.search.degraded")}
                </p>
              )}
            </div>
          )}
          <div className="d-flex gap-16 align-items-center mb-16">
            <SegmentedControl
              options={[
                { label: t("workspace.react.view.icons"), value: "icons" },
                { label: t("workspace.react.view.list"), value: "list" },
              ]}
              value={view}
              onChange={(v: string) =>
                changeView(v === "list" ? "list" : "icons")
              }
            />
            <label>
              {t("workspace.react.sort")}{" "}
              <select
                value={sort.key}
                onChange={(e) => changeSort(e.target.value as SortKey, false)}
                data-workspace-sort
              >
                <option value="name">{t("name")}</option>
                <option value="owner">{t("owner")}</option>
                <option value="modified">{t("modified")}</option>
                <option value="size">{t("size")}</option>
              </select>
            </label>
            <button
              type="button"
              className="btn btn-outline-secondary btn-sm"
              onClick={() => changeSort(sort.key)}
              aria-label={t(
                sort.desc
                  ? "workspace.react.sort.desc"
                  : "workspace.react.sort.asc",
              )}
              title={t(
                sort.desc
                  ? "workspace.react.sort.desc"
                  : "workspace.react.sort.asc",
              )}
              data-workspace-sort-direction
            >
              {sort.desc ? "↓" : "↑"}
            </button>
          </div>
          <ActionBar
            actions={actions}
            count={selection.length}
            onAction={onAction}
            onClear={() => setSelected(new Set())}
          />
          {validTree === "trash" && elements.length > 0 && (
            <div className="mb-16">
              <button
                type="button"
                className="btn btn-outline-danger"
                data-action="empty-trash"
                onClick={() => setConfirm("empty")}
              >
                {t("workspace.empty.trash")}
              </button>
            </div>
          )}
          {notice && (
            <div
              className="alert alert-warning"
              role="alert"
              data-workspace-notice
            >
              {notice}
            </div>
          )}
          <UploadPanel items={uploads.items} onClear={uploads.clear} />
          {content.isLoading && <LoadingScreen />}
          {content.isError && (
            <div
              className="alert alert-danger"
              role="alert"
              data-workspace-error
            >
              {t("workspace.react.error")}{" "}
              <button
                type="button"
                className="btn btn-outline-secondary btn-sm"
                onClick={() => content.refetch()}
              >
                {t("workspace.react.retry")}
              </button>
            </div>
          )}
          {content.isSuccess && elements.length === 0 && (
            <p data-workspace-empty>{t("workspace.react.empty")}</p>
          )}
          {content.isSuccess && elements.length > 0 && (
            <>
              <ElementList
                tree={validTree}
                elements={visible}
                view={view}
                selected={selected}
                onToggle={toggle}
                onToggleAll={toggleAll}
                sort={sort}
                onSort={(key) => changeSort(key)}
                publishedIds={published.data}
                searching={!!query}
                onShowComments={setCommenting}
                onDragStart={validTree === "owner" ? onDragStart : undefined}
                onDropOnFolder={
                  validTree === "owner" ? (f) => moveDragged(f._id) : undefined
                }
                canDropOn={(f, ev) => canDrop(f._id, ev)}
              />
              <LoadMore
                remaining={elements.length - visible.length}
                onMore={() => setShown((n) => n + PAGE_SIZE)}
              />
            </>
          )}
        </main>
      </div>
      <ShareWarning
        element={warnShare}
        busy={copyingForShare}
        onClose={() => setWarnShare(null)}
        onShare={() => {
          setSharing(warnShare);
          setWarnShare(null);
        }}
        onShareCopy={async () => {
          if (!warnShare) return;
          const originals = shareSet.length ? shareSet : [warnShare];
          setCopyingForShare(true);
          try {
            const copies = await elementActions.copy(originals);
            // chaque copie remplace son original, dans le même ordre
            const copied = originals
              .map((o) => {
                const copy = copies.find((c) => c.copyFromId === o._id);
                return copy ? { ...o, ...copy } : undefined;
              })
              .filter((c): c is WorkspaceElement => !!c);
            await Promise.all(
              ["content", "folders", "quota"].map((k) =>
                qc.invalidateQueries({ queryKey: ["workspace", k] }),
              ),
            );
            if (copied.length) {
              setShareSet(copied);
              setSharing(copied[0]);
            }
            setWarnShare(null);
          } catch {
            setNotice(t("workspace.react.action.failed"));
            setWarnShare(null);
          } finally {
            setCopyingForShare(false);
          }
        }}
      />
      {sharing && (
        <ShareModal
          isOpen
          shareOptions={{
            resourceId: sharing._id,
            resourceRights: [],
            resourceCreatorId: sharing.owner,
            resourceCreatorDisplayName: sharing.ownerName,
          }}
          onCancel={() => setSharing(null)}
          onSuccess={() => {
            const first = sharing;
            const others = shareSet.filter((e) => e._id !== first._id).map((e) => e._id);
            // plusieurs éléments : les droits réglés sur le premier sont recopiés sur les autres, puis on rafraîchit
            const copied = others.length ? elementActions.shareLike(first._id, others).catch(() => setNotice(t("workspace.react.action.failed"))) : Promise.resolve();
            setSharing(null);
            setSelected(new Set());
            void copied.then(() => Promise.all(["content", "folders"].map((k) => qc.invalidateQueries({ queryKey: ["workspace", k] }))));
          }}
        />
      )}
      <NextcloudPicker
        isOpen={ncPicker !== null}
        userId={user?.userId}
        title={t(
          ncPicker === "move"
            ? "nextcloud.share.move.action"
            : "nextcloud.share.copy.action",
        )}
        onClose={() => setNcPicker(null)}
        onPick={(dest) => {
          const verb = ncPicker === "move" ? "move" : "copy";
          setNcPicker(null);
          if (user?.userId)
            void run(() =>
              nextcloudService.fromWorkspace(
                user.userId,
                verb,
                selection.map((e) => e._id),
                dest || undefined,
              ),
            );
        }}
      />
      <LoolCreateModal
        isOpen={loolCreate}
        folderId={folderId}
        onClose={() => setLoolCreate(false)}
        onResync={() =>
          void Promise.all(
            ["content", "folders", "quota"].map((k) =>
              qc.invalidateQueries({ queryKey: ["workspace", k] }),
            ),
          )
        }
      />
      <VersionsModal
        element={versions}
        rights={
          versions
            ? rightsOn(versions, user?.userId, groupIds)
            : { read: false, contrib: false, manager: false, comment: false }
        }
        userId={user?.userId}
        onClose={() => setVersions(null)}
        onChanged={() =>
          void Promise.all(
            ["content", "quota"].map((k) =>
              qc.invalidateQueries({ queryKey: ["workspace", k] }),
            ),
          )
        }
      />
      <CommentsModal
        element={commenting}
        rights={
          commenting
            ? rightsOn(commenting, user?.userId, groupIds)
            : { read: false, contrib: false, manager: false, comment: false }
        }
        userId={user?.userId}
        userName={user?.username}
        onClose={() => setCommenting(null)}
        onChanged={() =>
          void qc.invalidateQueries({ queryKey: ["workspace", "content"] })
        }
      />
      <PublishModal
        element={publishing}
        published={!!publishing && !!published.data?.includes(publishing._id)}
        onClose={() => setPublishing(null)}
        onChanged={() =>
          void Promise.all(
            ["content", "published"].map((k) =>
              qc.invalidateQueries({ queryKey: ["workspace", k] }),
            ),
          )
        }
      />
      <FolderPicker
        isOpen={picker !== null}
        title={
          picker === "copy"
            ? t("workspace.react.picker.copy")
            : t("workspace.react.picker.move")
        }
        folders={folders.data ?? []}
        forbidden={forbiddenTargets(folders.data ?? [], selection)}
        onClose={() => setPicker(null)}
        onPick={(dest) => {
          const mode = picker;
          setPicker(null);
          void run(() =>
            mode === "copy"
              ? elementActions.copy(selection, dest ?? undefined)
              : elementActions.move(selection, dest ?? undefined),
          );
        }}
      />
      <RenameModal
        element={renaming}
        error={renameError}
        onClose={() => setRenaming(null)}
        onRename={async (name) => {
          if (!renaming) return;
          const target = renaming;
          if (await run(() => elementActions.rename(target, name)))
            setRenaming(null);
          else setRenameError(t("workspace.react.action.failed"));
        }}
      />
      <ConfirmModal
        isOpen={confirm !== null}
        title={
          confirm === "empty"
            ? t("workspace.empty.trash")
            : confirm === "trashShared"
              ? t("workspace.delete.confirm.title")
              : t("workspace.react.action.delete")
        }
        message={
          confirm === "empty"
            ? t("workspace.delete.empty.trash")
            : confirm === "trashShared"
              ? t("workspace.react.confirm.trash.shared")
              : t("workspace.react.confirm.delete")
        }
        confirmLabel={
          confirm === "empty"
            ? t("workspace.empty.trash")
            : confirm === "trashShared"
              ? t("workspace.delete.confirm.action")
              : t("workspace.react.action.delete")
        }
        onClose={() => setConfirm(null)}
        onConfirm={() => {
          const mode = confirm;
          setConfirm(null);
          if (mode === "trashShared")
            void run(() => elementActions.trash(selection));
          else
            void run(() =>
              mode === "empty"
                ? elementActions.emptyTrash()
                : elementActions.deleteForever(selection),
            );
        }}
      />
      <NewFolderModal
        parentId={folderId}
        isOpen={newFolderOpen}
        onClose={() => setNewFolderOpen(false)}
      />
      <ZipDialog
        file={pendingZip}
        onClose={() => setPendingZip(null)}
        onChoose={(uncompress) => {
          const file = pendingZip;
          setPendingZip(null);
          if (file) void uploads.send([file], uncompress);
        }}
      />
    </>
  );
};
