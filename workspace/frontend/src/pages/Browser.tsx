import { AppHeader, LoadingScreen, SegmentedControl, useEdificeClient } from "@open-ent/react";
import { useQueryClient } from "@tanstack/react-query";
import { DragEvent, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, Navigate, useParams } from "react-router-dom";
import { ActionBar } from "../components/ActionBar";
import { ConfirmModal } from "../components/ConfirmModal";
import { ElementList } from "../components/ElementList";
import { FolderPicker } from "../components/FolderPicker";
import { RenameModal } from "../components/RenameModal";
import { NewFolderModal } from "../components/NewFolderModal";
import { UploadPanel } from "../components/UploadPanel";
import { ZipDialog } from "../components/ZipDialog";
import { QuotaGauge } from "../components/QuotaGauge";
import { TreeNav } from "../components/TreeNav";
import { ActionId, availableActions, downloadUrl, forbiddenTargets } from "../lib/actions";
import { breadcrumb, isTreeName, sortElements, SortKey } from "../lib/elements";
import { isZip, planUpload, remainingLabel } from "../lib/upload";
import { ViewMode, WorkspaceElement } from "../lib/types";
import { useUploads } from "../services/useUploads";
import { elementActions } from "../services/workspace.service";
import { useContent, useFolders, useQuota, useViewModePreference } from "../services/queries";

/** Navigation dans l'espace documentaire : arbres, dossiers, fil d'Ariane, liste en icônes ou en tableau, jauge d'espace. */
export const Browser = () => {
  const { t } = useTranslation("workspace");
  const { tree, folderId } = useParams();
  const [view, setView] = useState<ViewMode>("icons");
  const [sort, setSort] = useState<SortKey>("name");

  const preference = useViewModePreference();
  useEffect(() => {
    if (preference.data) setView(preference.data);
  }, [preference.data]);

  const validTree = isTreeName(tree) ? tree : "owner";
  const content = useContent(validTree, folderId);
  const folders = useFolders();
  const { user } = useEdificeClient();
  const quota = useQuota(user?.userId);

  // Création et import : dans « Mes documents » et ses dossiers (le partage au dépôt arrive avec le lot W4).
  const canWrite = validTree === "owner";
  const uploads = useUploads(folderId);
  const [newFolderOpen, setNewFolderOpen] = useState(false);
  const [pendingZip, setPendingZip] = useState<File | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const elements = useMemo(() => sortElements(content.data ?? [], sort), [content.data, sort]);

  // Sélection et actions de gestion
  const qc = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [picker, setPicker] = useState<"copy" | "move" | null>(null);
  const [renaming, setRenaming] = useState<WorkspaceElement | null>(null);
  const [renameError, setRenameError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<"delete" | "empty" | null>(null);
  useEffect(() => setSelected(new Set()), [tree, folderId]);

  const selection = useMemo(() => elements.filter((e) => selected.has(e._id)), [elements, selected]);
  const actions = availableActions(validTree, selection);
  const toggle = (id: string) =>
    setSelected((cur) => {
      const next = new Set(cur);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  const toggleAll = () => setSelected((cur) => (cur.size === elements.length ? new Set() : new Set(elements.map((e) => e._id))));

  /** Exécute une action serveur, rafraîchit la liste, l'arborescence et la jauge ; un échec est signalé. */
  const run = async (fn: () => Promise<unknown>) => {
    setNotice(null);
    try {
      await fn();
      setSelected(new Set());
      await Promise.all(["content", "folders", "quota"].map((k) => qc.invalidateQueries({ queryKey: ["workspace", k] })));
      return true;
    } catch {
      setNotice(t("workspace.react.action.failed"));
      return false;
    }
  };

  const onAction = (a: ActionId) => {
    if (a === "download") window.location.assign(downloadUrl(selection));
    else if (a === "trash") void run(() => elementActions.trash(selection));
    else if (a === "restore") void run(() => elementActions.restore(selection));
    else if (a === "delete") setConfirm("delete");
    else if (a === "rename") {
      setRenameError(null);
      setRenaming(selection[0]);
    } else setPicker(a);
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
    e.preventDefault();
    setDragging(false);
    if (!canWrite) return;
    const items = [...e.dataTransfer.items];
    // Un dossier déposé ne peut pas être importé : seuls les fichiers le sont (comme dans l'ancienne interface).
    if (items.some((i) => i.webkitGetAsEntry?.()?.isDirectory)) {
      setNotice(`${t("workspace.import.warning")}. ${t("workspace.import.solution").replace(/<[^>]+>/g, "")}`);
      return;
    }
    importFiles([...e.dataTransfer.files]);
  };

  const path = useMemo(() => breadcrumb(folders.data ?? [], folderId ?? null), [folders.data, folderId]);

  if (!isTreeName(tree)) return <Navigate to="/owner" replace />;

  return (
    <>
      <AppHeader>
        <h1 className="h2" data-workspace-title>
          {t("workspace.title")}
        </h1>
      </AppHeader>
      <div className="d-flex gap-24 flex-wrap">
        <aside style={{ minWidth: 240 }}>
          <TreeNav tree={validTree} folderId={folderId} folders={folders.data ?? []} />
          {quota.data && <QuotaGauge quota={quota.data} />}
        </aside>
        <main
          style={{ flex: 1, minWidth: 0, outline: dragging && canWrite ? "2px dashed #2a9cc8" : undefined }}
          data-workspace-dropzone
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
        >
          <nav aria-label={t("workspace.react.breadcrumb")} data-workspace-breadcrumb className="mb-12">
            <Link to={`/${validTree}`}>{t(`workspace.react.tree.${validTree}`)}</Link>
            {path.map((f) => (
              <span key={f._id}>
                {" / "}
                <Link to={`/${validTree}/${f._id}`}>{f.name}</Link>
              </span>
            ))}
          </nav>
          <div className="d-flex gap-16 align-items-center mb-16">
            <SegmentedControl
              options={[
                { label: t("workspace.react.view.icons"), value: "icons" },
                { label: t("workspace.react.view.list"), value: "list" },
              ]}
              value={view}
              onChange={(v: string) => setView(v === "list" ? "list" : "icons")}
            />
            <label>
              {t("workspace.react.sort")}{" "}
              <select value={sort} onChange={(e) => setSort(e.target.value as SortKey)} data-workspace-sort>
                <option value="name">{t("name")}</option>
                <option value="modified">{t("modified")}</option>
                <option value="size">{t("size")}</option>
              </select>
            </label>
          </div>
          <ActionBar actions={actions} count={selection.length} onAction={onAction} onClear={() => setSelected(new Set())} />
          {validTree === "trash" && elements.length > 0 && (
            <div className="mb-16">
              <button type="button" className="btn btn-outline-danger" data-action="empty-trash" onClick={() => setConfirm("empty")}>
                {t("workspace.empty.trash")}
              </button>
            </div>
          )}
          {canWrite && (
            <div className="d-flex gap-12 mb-16" data-workspace-toolbar>
              <button type="button" className="btn btn-primary" onClick={() => setNewFolderOpen(true)} data-action="new-folder">
                {t("folder.new")}
              </button>
              <button type="button" className="btn btn-outline-primary" onClick={() => fileInput.current?.click()} data-action="import">
                {t("import.file")}
              </button>
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
          {notice && (
            <div className="alert alert-warning" role="alert" data-workspace-notice>
              {notice}
            </div>
          )}
          <UploadPanel items={uploads.items} onClear={uploads.clear} />
          {content.isLoading && <LoadingScreen />}
          {content.isError && (
            <div className="alert alert-danger" role="alert" data-workspace-error>
              {t("workspace.react.error")}{" "}
              <button type="button" className="btn btn-link p-0" onClick={() => content.refetch()}>
                {t("workspace.react.retry")}
              </button>
            </div>
          )}
          {content.isSuccess && elements.length === 0 && <p data-workspace-empty>{t("workspace.react.empty")}</p>}
          {content.isSuccess && elements.length > 0 && <ElementList tree={validTree} elements={elements} view={view} selected={selected} onToggle={toggle} onToggleAll={toggleAll} />}
        </main>
      </div>
      <FolderPicker
        isOpen={picker !== null}
        title={picker === "copy" ? t("workspace.react.picker.copy") : t("workspace.react.picker.move")}
        folders={folders.data ?? []}
        forbidden={forbiddenTargets(folders.data ?? [], selection)}
        onClose={() => setPicker(null)}
        onPick={(dest) => {
          const mode = picker;
          setPicker(null);
          void run(() => (mode === "copy" ? elementActions.copy(selection, dest ?? undefined) : elementActions.move(selection, dest ?? undefined)));
        }}
      />
      <RenameModal
        element={renaming}
        error={renameError}
        onClose={() => setRenaming(null)}
        onRename={async (name) => {
          if (!renaming) return;
          const target = renaming;
          if (await run(() => elementActions.rename(target, name))) setRenaming(null);
          else setRenameError(t("workspace.react.action.failed"));
        }}
      />
      <ConfirmModal
        isOpen={confirm !== null}
        title={confirm === "empty" ? t("workspace.empty.trash") : t("workspace.react.action.delete")}
        message={confirm === "empty" ? t("workspace.delete.empty.trash") : t("workspace.react.confirm.delete")}
        confirmLabel={confirm === "empty" ? t("workspace.empty.trash") : t("workspace.react.action.delete")}
        onClose={() => setConfirm(null)}
        onConfirm={() => {
          const mode = confirm;
          setConfirm(null);
          void run(() => (mode === "empty" ? elementActions.emptyTrash() : elementActions.deleteForever(selection)));
        }}
      />
      <NewFolderModal parentId={folderId} isOpen={newFolderOpen} onClose={() => setNewFolderOpen(false)} />
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
