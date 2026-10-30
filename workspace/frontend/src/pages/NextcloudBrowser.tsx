import { AppHeader, LoadingScreen, SegmentedControl, ShareModal, useEdificeClient } from "@open-ent/react";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { ConfirmModal } from "../components/ConfirmModal";
import { FolderPicker } from "../components/FolderPicker";
import { NextcloudNav } from "../components/NextcloudNav";
import { QuotaGauge } from "../components/QuotaGauge";
import { SelectionBar } from "../components/SelectionBar";
import { BarLayout } from "../lib/actionGroups";
import { TreeNav } from "../components/TreeNav";
import { fileKind, formatSize, parseDate, SortKey } from "../lib/elements";
import { nextSort, SORT_FIELD, sortKeyOf, viewOf } from "../lib/preferences";
import { ViewMode } from "../lib/types";
import { folderNameError } from "../lib/upload";
import {
  downloadUrl,
  folderPathFor,
  multiDownloadUrl,
  NC_DRAG_TYPE,
  NEW_DOCUMENT_TYPES,
  dropMoves,
  NewDocumentType,
  newDocumentNameError,
  NextcloudActionId,
  nextcloudActions,
  parentPath,
  pathCrumbs,
  renamedPath,
  sortSyncDocuments,
  SyncDocument,
} from "../lib/nextcloud";
import { nextcloudService } from "../services/nextcloud.service";
import { elementActions, workspaceService } from "../services/workspace.service";
import { useFolders, useFolderStore, useNextcloudList, useNextcloudQuota, useNextcloudUrl, useQuota, useWorkspacePreference } from "../services/queries";

const ICON: Record<string, string> = { folder: "📁", image: "🖼️", pdf: "📕", doc: "📝", sheet: "📊", slides: "📽️", audio: "🎵", video: "🎞️", text: "📄", archive: "🗜️", other: "📎" };
const LABEL: Record<NextcloudActionId, string> = {
  download: "workspace.react.action.download",
  share: "workspace.react.action.share",
  edit: "workspace.external.open",
  rename: "workspace.rename",
  trash: "nextcloud.documents.trash",
  copyToWorkspace: "workspace.react.nc.copy.workspace",
  moveToWorkspace: "workspace.react.nc.move.workspace",
  restore: "workspace.trash.restore",
  deleteForever: "workspace.react.action.delete",
};

/** Même présentation que l'espace documentaire : actions fréquentes en boutons, transferts vers l'espace documentaire dans « Organiser », corbeille à droite. */
const LAYOUT: BarLayout<NextcloudActionId> = {
  direct: ["download", "edit", "rename", "share", "restore"],
  menus: [{ key: "organise", label: "workspace.react.menu.organise", ids: ["copyToWorkspace", "moveToWorkspace"] }],
  danger: ["trash", "deleteForever"],
};

/**
 * « Documents synchronisés » : le compte NextCloud de l'usager vu depuis l'espace documentaire — dossiers, envoi de fichiers,
 * corbeille NextCloud, transfert vers l'espace documentaire. Le chemin courant est le paramètre `path` de l'adresse.
 */
export const NextcloudBrowser = ({ trash = false }: { trash?: boolean }) => {
  const { t } = useTranslation("workspace");
  const { user } = useEdificeClient();
  const userId = user?.userId;
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [params] = useSearchParams();
  const path = (params.get("path") ?? "").replace(/^\/+|\/+$/g, "");
  const list = useNextcloudList(userId, path, trash, true);
  const link = useNextcloudUrl(true);
  const folders = useFolders();
  const folderStore = useFolderStore();
  const lazy = folderStore.lazy ? { isLoaded: folderStore.isLoaded, onExpand: folderStore.loadChildren } : undefined;
  const quota = useQuota(user?.userId);
  const ncRoot = useNextcloudList(userId, "", false, true);
  const ncQuota = useNextcloudQuota(userId, true);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [notice, setNotice] = useState<string | null>(null);
  const [picker, setPicker] = useState<"copy" | "move" | null>(null);
  const [renaming, setRenaming] = useState<SyncDocument | null>(null);
  const [newName, setNewName] = useState("");
  const [creating, setCreating] = useState(false);
  const [newDoc, setNewDoc] = useState<{ type: NewDocumentType; name: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  useEffect(() => setSelected(new Set()), [path, trash]);

  // Tri des colonnes, mémorisé dans la même préférence que les documents personnels
  const preference = useWorkspacePreference();
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: "name", desc: false });
  const [view, setView] = useState<ViewMode>("icons");
  const sortApplied = useRef(false);
  useEffect(() => {
    if (!preference.data || sortApplied.current) return;
    sortApplied.current = true;
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
    void workspaceService.savePreference({ sortField: SORT_FIELD[next.key], sortDesc: next.desc });
  };
  const docs = useMemo(() => sortSyncDocuments(list.data ?? [], sort.key, sort.desc), [list.data, sort]);
  const selection = docs.filter((d) => selected.has(d.path));
  const actions = nextcloudActions(selection, trash);
  const refresh = () => qc.invalidateQueries({ queryKey: ["workspace", "nextcloud"] });
  const run = async (fn: () => Promise<unknown>) => {
    setNotice(null);
    setBusy(true);
    try {
      await fn();
      setSelected(new Set());
      await refresh();
      return true;
    } catch {
      setNotice(t("workspace.react.action.failed"));
      return false;
    } finally {
      setBusy(false);
    }
  };
  const paths = selection.map((d) => d.path);

  /** Document bureautique : éditeur en ligne via une adresse à jeton fabriquée par le connecteur (aucune connexion NextCloud demandée). */
  const openEditor = async (d: SyncDocument) => {
    if (!userId) return;
    setNotice(null);
    const win = window.open("", "_blank"); // ouvert tout de suite pour ne pas être bloqué comme fenêtre surgie après l'appel
    try {
      const url = await nextcloudService.editUrl(userId, d.path);
      if (win) win.location.href = url;
      else window.open(url);
    } catch {
      win?.close();
      setNotice(t("workspace.react.nc.edit.error"));
    }
  };
  /** Un clic sur un fichier : l'éditeur en ligne s'il est modifiable, sinon la visionneuse de l'application (texte, tableau, PDF, image, son, vidéo). */
  const openFile = (d: SyncDocument) => {
    if (d.editable) void openEditor(d);
    else navigate(`/nextcloud-view?path=${encodeURIComponent(d.path)}`);
  };

  // Glisser-déposer : lâcher des éléments sur un dossier NextCloud les y déplace
  const draggedPaths = useRef<string[]>([]);
  const [overFolder, setOverFolder] = useState<string | null>(null);
  const dropAllowed = (ev: React.DragEvent, dest: string) => !trash && ev.dataTransfer.types.includes(NC_DRAG_TYPE) && dropMoves(draggedPaths.current, dest).length > 0;
  const dropOn = (ev: React.DragEvent, dest: string) => {
    setOverFolder(null);
    if (!dropAllowed(ev, dest) || !userId) return;
    ev.preventDefault();
    const moves = dropMoves(draggedPaths.current, dest);
    draggedPaths.current = [];
    void run(async () => {
      for (const m of moves) await nextcloudService.move(userId, m.from, m.to);
    });
  };

  /** Glisser-déposer d'un élément (ligne ou tuile) : on emporte toute la sélection s'il en fait partie, sinon lui seul ; un dossier reçoit les éléments lâchés. */
  const dndProps = (d: SyncDocument) => ({
    draggable: !trash,
    onDragStart: (ev: React.DragEvent) => {
      const moved = selected.has(d.path) ? [...selected] : [d.path];
      draggedPaths.current = moved;
      ev.dataTransfer.setData(NC_DRAG_TYPE, JSON.stringify(moved));
      ev.dataTransfer.effectAllowed = "move";
    },
    onDragOver: d.isFolder ? (ev: React.DragEvent) => dropAllowed(ev, d.path) && (ev.preventDefault(), setOverFolder(d.path)) : undefined,
    onDragLeave: d.isFolder ? () => setOverFolder(null) : undefined,
    onDrop: d.isFolder ? (ev: React.DragEvent) => dropOn(ev, d.path) : undefined,
  });

  // Partage d'un document NextCloud : un exemplaire est placé dans l'espace documentaire et partagé là ; annuler le remet dans NextCloud
  const [shareWarn, setShareWarn] = useState(false);
  const [sharing, setSharing] = useState<Array<{ _id: string; name: string }>>([]);
  const startShare = async () => {
    setShareWarn(false);
    if (!userId) return;
    setBusy(true);
    try {
      const copies = await nextcloudService.toWorkspace(userId, "copy", paths);
      if (Array.isArray(copies) && copies.length) setSharing(copies);
      else setNotice(t("workspace.react.action.failed"));
    } catch {
      setNotice(t("workspace.react.action.failed"));
    } finally {
      setBusy(false);
    }
  };
  const cancelShare = async () => {
    const copies = sharing;
    setSharing([]);
    if (userId && copies.length) await run(() => nextcloudService.fromWorkspace(userId, "move", copies.map((c) => c._id), path || undefined));
  };

  const onAction = (a: NextcloudActionId) => {
    if (!userId) return;
    if (a === "download") window.location.assign(selection.length === 1 ? downloadUrl(userId, selection[0]) : multiDownloadUrl(userId, path, selection.map((d) => d.name)));
    else if (a === "edit") void openEditor(selection[0]);
    else if (a === "rename") {
      setRenaming(selection[0]);
      setNewName(selection[0].name);
    } else if (a === "trash") void run(() => nextcloudService.trash(userId, paths));
    else if (a === "restore") void run(() => nextcloudService.restore(userId, paths));
    else if (a === "deleteForever") void run(() => nextcloudService.deleteForever(userId, paths));
    else if (a === "share") setShareWarn(true);
    else if (a === "copyToWorkspace") setPicker("copy");
    else if (a === "moveToWorkspace") setPicker("move");
  };

  const toggle = (p: string) =>
    setSelected((cur) => {
      const next = new Set(cur);
      if (!next.delete(p)) next.add(p);
      return next;
    });
  const open = (d: SyncDocument) =>
    d.isFolder ? (
      <Link to={`/nextcloud?path=${encodeURIComponent(d.path)}`} data-nc-open>
        {d.name}
      </Link>
    ) : (
      <a
        href={userId ? downloadUrl(userId, d, true) : "#"}
        data-nc-open
        onClick={(e) => {
          e.preventDefault();
          openFile(d);
        }}
      >
        {d.name}
      </a>
    );

  return (
    <>
      <AppHeader>
        <h1 className="h2" data-nc-title>
          {t("workspace.title")}
        </h1>
      </AppHeader>
      <div className="d-flex gap-24 flex-wrap">
        <aside className="ws-aside" style={{ flex: "0 0 260px", maxWidth: "100%" }}>
          <TreeNav tree="owner" highlight={false} keepOpen folders={folders.data ?? []} lazy={lazy} userId={userId}>
            {quota.data && <QuotaGauge quota={quota.data} />}
          </TreeNav>
          <NextcloudNav
            userId={userId}
            active
            trash={trash}
            folders={(ncRoot.data ?? []).filter((d) => d.isFolder)}
            currentPath={path}
            quota={ncQuota.data}
            onCreateFolder={
              trash
                ? undefined
                : () => {
                    setCreating(true);
                    setNewName("");
                  }
            }
          />
        </aside>
        <main className="ws-main" style={{ flex: "1 1 320px", minWidth: 0 }}>
          {link.data && !link.data.hidden && link.data.url && (
            <p data-nc-url>
              {t("nextcloud.url.connect")} :{" "}
              <a href={link.data.url} target="_blank" rel="noreferrer">
                {link.data.url}
              </a>
            </p>
          )}
          {!trash && (
            <nav aria-label={t("workspace.react.breadcrumb")} data-nc-breadcrumb className="mb-12">
              <Link to="/nextcloud">{t("nextcloud.documents")}</Link>
              {pathCrumbs(path).map((c) => (
                <span key={c.path}>
                  {" / "}
                  <Link to={`/nextcloud?path=${encodeURIComponent(c.path)}`}>{c.name}</Link>
                </span>
              ))}
            </nav>
          )}
          {!trash && (
            <div className="d-flex gap-12 mb-16" data-nc-toolbar>
              <button type="button" className="btn btn-primary" onClick={() => setNewDoc({ type: "docx", name: "" })} data-nc-action="create-document">
                {t("workspace.react.nc.create.document")}
              </button>
              <button type="button" className="btn btn-outline-primary" onClick={() => fileInput.current?.click()} data-nc-action="upload">
                {t("import.file")}
              </button>
              <button
                type="button"
                className="btn btn-outline-primary"
                onClick={() => {
                  setCreating(true);
                  setNewName("");
                }}
                data-nc-toolbar-new-folder
              >
                {t("folder.new")}
              </button>
              {/* ouvre NextCloud déjà connecté : la connexion unique reconnaît l'usager de l'ENT ; à droite, aligné sur les autres boutons */}
              {link.data?.url && (
                <a className="btn btn-outline-primary" style={{ marginLeft: "auto" }} href={link.data.url} target="_blank" rel="noreferrer" title={t("workspace.react.nc.open.server.hint")} data-nc-open-server>
                  {t("workspace.react.nc.open.server")}
                </a>
              )}
              <input
                ref={fileInput}
                type="file"
                multiple
                hidden
                data-nc-upload-input
                onChange={(e) => {
                  const files = [...(e.target.files ?? [])];
                  e.target.value = "";
                  if (files.length && userId) void run(() => nextcloudService.upload(userId, files, path));
                }}
              />
            </div>
          )}
          {trash && link.data?.url && (
            <div className="d-flex gap-12 mb-16">
              <a className="btn btn-outline-primary" style={{ marginLeft: "auto" }} href={link.data.url} target="_blank" rel="noreferrer" title={t("workspace.react.nc.open.server.hint")} data-nc-open-server>
                {t("workspace.react.nc.open.server")}
              </a>
            </div>
          )}
          {trash && docs.length > 0 && (
            <button type="button" className="btn btn-outline-danger mb-16" data-nc-action="empty-trash" onClick={() => userId && void run(() => nextcloudService.emptyTrash(userId))}>
              {t("workspace.empty.trash")}
            </button>
          )}
          <SelectionBar actions={actions} layout={LAYOUT} labels={LABEL} count={selection.length} attr={{ bar: "data-nc-action-bar", action: "data-nc-action" }} busy={busy} onAction={onAction} onClear={() => setSelected(new Set())} />
          {notice && (
            <div className="alert alert-warning" role="alert" data-nc-notice>
              {notice}
            </div>
          )}
          {list.isLoading && <LoadingScreen />}
          {list.isError && (
            <div className="alert alert-danger" role="alert" data-nc-error>
              {t("workspace.react.error")}{" "}
              <button type="button" className="btn btn-outline-secondary btn-sm" onClick={() => list.refetch()}>
                {t("workspace.react.retry")}
              </button>
            </div>
          )}
          {list.isSuccess && docs.length === 0 && (
            <div className="text-center my-24" data-nc-empty>
              <h2 className="h4">{t(trash ? "empty.trash2" : path ? "empty.workspace.subfolder.title" : "empty.workspace.title")}</h2>
              <img src="/workspace/public/img/empty-workspace.svg" alt="" style={{ maxWidth: 160 }} data-empty-image />
              {!trash && !path && <p className="text-muted">{t("empty.workspace")}</p>}
            </div>
          )}
          {list.isSuccess && docs.length > 0 && (
            <div className="d-flex gap-16 align-items-center mb-16">
              <SegmentedControl
                options={[
                  { label: t("workspace.react.view.icons"), value: "icons" },
                  { label: t("workspace.react.view.list"), value: "list" },
                ]}
                value={view}
                onChange={(v: string) => changeView(v === "list" ? "list" : "icons")}
              />
              <label>
                {t("workspace.react.sort")}{" "}
                <select value={sort.key} onChange={(e) => changeSort(e.target.value as SortKey, false)} data-nc-sort>
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
                aria-label={t(sort.desc ? "workspace.react.sort.desc" : "workspace.react.sort.asc")}
                title={t(sort.desc ? "workspace.react.sort.desc" : "workspace.react.sort.asc")}
                data-nc-sort-direction
              >
                {sort.desc ? "↓" : "↑"}
              </button>
            </div>
          )}
          {list.isSuccess && docs.length > 0 && view === "icons" && (
            <ul className="list-unstyled d-flex flex-wrap gap-16" data-nc-list="icons">
              {docs.map((d) => {
                const isSelected = selected.has(d.path);
                return (
                  <li
                    key={d.path}
                    data-nc-item={d.isFolder ? "folder" : "file"}
                    data-nc-path={d.path}
                    data-selected={isSelected}
                    style={{ width: 132, cursor: "pointer" }}
                    onClick={(ev) => {
                      if ((ev.target as HTMLElement).closest("a,input,button")) return;
                      // un clic sur un dossier y entre ; Maj/Ctrl + clic le sélectionne (la case à cocher aussi) ; un document se sélectionne
                      if (d.isFolder && !trash && !ev.shiftKey && !ev.ctrlKey && !ev.metaKey) navigate(`/nextcloud?path=${encodeURIComponent(d.path)}`);
                      else toggle(d.path);
                    }}
                    {...dndProps(d)}
                  >
                    <div
                      style={{
                        position: "relative",
                        background: "#fff",
                        borderRadius: 8,
                        overflow: "hidden",
                        boxShadow: "0 1px 4px rgba(0,0,0,.25)",
                        outline: overFolder === d.path ? "2px dashed #2a9cc8" : isSelected ? "3px solid #2a9cc8" : "none",
                      }}
                    >
                      <div style={{ position: "absolute", top: 6, left: 8 }}>
                        <input type="checkbox" data-nc-select checked={isSelected} onChange={() => toggle(d.path)} aria-label={d.name} />
                      </div>
                      <div style={{ height: 96, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 46 }} aria-hidden="true">
                        {ICON[d.isFolder ? "folder" : fileKind(d.contentType)]}
                      </div>
                      <div style={{ background: isSelected ? "#2a9cc8" : "#7a7a7a", color: "#fff", padding: "5px 8px", fontSize: 13, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {trash ? d.name : open(d)}
                      </div>
                    </div>
                    <small className="d-block text-muted text-center mt-4">{d.isFolder ? "" : formatSize(d.size)}</small>
                  </li>
                );
              })}
            </ul>
          )}
          {list.isSuccess && docs.length > 0 && view === "list" && (
            <table className="table w-100" data-nc-list>
              <thead>
                <tr>
                  <th />
                  {(["name", "owner", "modified", "size"] as const).map((key) => (
                    <th key={key} className={key === "owner" ? "ws-col-owner" : key === "modified" ? "ws-col-date" : key === "size" ? "ws-col-size" : undefined} aria-sort={sort.key === key ? (sort.desc ? "descending" : "ascending") : undefined}>
                      <button type="button" className="btn btn-link p-0" style={{ color: "inherit", font: "inherit", fontWeight: 600 }} onClick={() => changeSort(key)} data-nc-sort-column={key}>
                        {t(key)} {sort.key === key ? (sort.desc ? "↓" : "↑") : ""}
                      </button>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {docs.map((d) => (
                  <tr
                    key={d.path}
                    data-nc-item={d.isFolder ? "folder" : "file"}
                    data-nc-path={d.path}
                    {...dndProps(d)}
                    style={overFolder === d.path ? { outline: "2px dashed #2a9cc8" } : undefined}
                  >
                    <td>
                      <input type="checkbox" data-nc-select checked={selected.has(d.path)} onChange={() => toggle(d.path)} aria-label={d.name} />
                    </td>
                    <td>
                      <span aria-hidden="true">{ICON[d.isFolder ? "folder" : fileKind(d.contentType)]}</span> {trash ? d.name : open(d)}
                    </td>
                    <td className="ws-col-owner">{d.ownerName}</td>
                    <td className="ws-col-date">{parseDate(d.lastModified?.replace("T", " ").replace(/\.\d+Z?$/, "").replace(/(\d\d:\d\d):(\d\d)/, "$1.$2"))?.toLocaleDateString("fr-FR") ?? ""}</td>
                    <td className="ws-col-size">{d.isFolder ? "" : formatSize(d.size)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </main>
      </div>

      {(creating || renaming) && (
        <div role="dialog" aria-modal="true" data-nc-name-dialog style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,.4)", zIndex: 2000, display: "flex", alignItems: "center", justifyContent: "center" }}>
          <form
            className="bg-white p-24 rounded"
            style={{ minWidth: 320 }}
            onSubmit={async (e) => {
              e.preventDefault();
              if (!userId) return;
              const name = newName.trim();
              if (renaming ? !name : folderNameError(name) === "empty") return;
              const ok = await run(() => (renaming ? nextcloudService.move(userId, renaming.path, renamedPath(renaming, name)) : nextcloudService.createFolder(userId, folderPathFor(path, name))));
              if (ok) {
                setCreating(false);
                setRenaming(null);
              }
            }}
          >
            <label htmlFor="nc-name">{renaming ? t("workspace.rename") : t("workspace.folder.name")}</label>
            <input id="nc-name" className="form-control" value={newName} onChange={(e) => setNewName(e.target.value)} autoComplete="off" />
            <div className="d-flex gap-8 mt-12 justify-content-end">
              <button type="button" className="btn btn-outline-secondary btn-sm" onClick={() => { setCreating(false); setRenaming(null); }}>
                {t("cancel")}
              </button>
              <button type="submit" className="btn btn-primary" data-nc-name-submit>
                {renaming ? t("workspace.rename") : t("workspace.folder.create")}
              </button>
            </div>
          </form>
        </div>
      )}
      {newDoc && (
        <div role="dialog" aria-modal="true" data-nc-create-dialog style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,.4)", zIndex: 2000, display: "flex", alignItems: "center", justifyContent: "center" }}>
          <form
            className="bg-white p-24 rounded"
            style={{ minWidth: 340 }}
            onSubmit={async (e) => {
              e.preventDefault();
              if (!userId || newDocumentNameError(newDoc.name)) return;
              if (await run(() => nextcloudService.createDocument(userId, newDoc.type, newDoc.name.trim(), path))) setNewDoc(null);
            }}
          >
            <h2 className="h4">{t("workspace.react.nc.create.document")}</h2>
            <fieldset className="mb-12">
              <legend className="h6">{t("workspace.react.nc.create.step1")}</legend>
              {NEW_DOCUMENT_TYPES.map((type) => (
                <label key={type} className="d-block">
                  <input type="radio" name="nc-new-type" value={type} checked={newDoc.type === type} onChange={() => setNewDoc({ ...newDoc, type })} data-nc-new-type={type} /> {t(`workspace.react.nc.create.type.${type}`)}
                </label>
              ))}
            </fieldset>
            <label htmlFor="nc-new-name" className="h6">
              {t("workspace.react.nc.create.step2")}
            </label>
            <input id="nc-new-name" className="form-control" value={newDoc.name} onChange={(e) => setNewDoc({ ...newDoc, name: e.target.value })} placeholder={t("workspace.react.nc.create.placeholder")} autoComplete="off" />
            <div className="d-flex gap-8 mt-12 justify-content-end">
              <button type="button" className="btn btn-outline-secondary btn-sm" onClick={() => setNewDoc(null)}>
                {t("cancel")}
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy || !!newDocumentNameError(newDoc.name)} data-nc-create-submit>
                {t("workspace.react.nc.create.validate")}
              </button>
            </div>
          </form>
        </div>
      )}
      <FolderPicker
        isOpen={picker !== null}
        title={picker === "copy" ? t("workspace.react.picker.copy") : t("workspace.react.picker.move")}
        folders={folders.data ?? []}
        forbidden={new Set()}
        lazy={lazy}
        onClose={() => setPicker(null)}
        onPick={(dest) => {
          const verb = picker === "move" ? "move" : "copy";
          setPicker(null);
          if (userId) void run(() => nextcloudService.toWorkspace(userId, verb, paths, dest ?? undefined));
        }}
      />
      {!trash && path && (
        <p className="mt-12">
          <button type="button" className="btn btn-outline-secondary btn-sm" onClick={() => navigate(`/nextcloud${parentPath(path) ? `?path=${encodeURIComponent(parentPath(path))}` : ""}`)} data-nc-up>
            ↑ {t("workspace.react.nc.up")}
          </button>
        </p>
      )}
      <ConfirmModal
        isOpen={shareWarn}
        title={t("workspace.react.nc.share.title")}
        message={t("workspace.react.nc.share.message")}
        confirmLabel={t("workspace.react.action.share")}
        onClose={() => setShareWarn(false)}
        onConfirm={() => void startShare()}
      />
      {sharing.length > 0 && (
        <ShareModal
          isOpen
          shareOptions={{ resourceId: sharing[0]._id, resourceRights: [], resourceCreatorId: user?.userId ?? "", resourceCreatorDisplayName: (user as { username?: string } | undefined)?.username ?? "" }}
          onCancel={() => void cancelShare()}
          onSuccess={() => {
            const first = sharing[0];
            const others = sharing.slice(1).map((c) => c._id);
            setSharing([]);
            setSelected(new Set());
            // plusieurs documents : les droits réglés sur le premier sont recopiés sur les autres
            if (others.length) void elementActions.shareLike(first._id, others).catch(() => setNotice(t("workspace.react.action.failed")));
          }}
        />
      )}
    </>
  );
};
