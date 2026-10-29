import { AppHeader, LoadingScreen, useEdificeClient } from "@open-ent/react";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { FolderPicker } from "../components/FolderPicker";
import { NextcloudNav } from "../components/NextcloudNav";
import { QuotaGauge } from "../components/QuotaGauge";
import { SelectionBar } from "../components/SelectionBar";
import { BarLayout } from "../lib/actionGroups";
import { TreeNav } from "../components/TreeNav";
import { fileKind, formatSize, parseDate } from "../lib/elements";
import { folderNameError } from "../lib/upload";
import {
  downloadUrl,
  folderPathFor,
  multiDownloadUrl,
  NEW_DOCUMENT_TYPES,
  NewDocumentType,
  newDocumentNameError,
  NextcloudActionId,
  nextcloudActions,
  parentPath,
  pathCrumbs,
  renamedPath,
  SyncDocument,
} from "../lib/nextcloud";
import { nextcloudService } from "../services/nextcloud.service";
import { useFolders, useNextcloudList, useNextcloudQuota, useNextcloudUrl, useQuota } from "../services/queries";

const ICON: Record<string, string> = { folder: "📁", image: "🖼️", pdf: "📕", doc: "📝", sheet: "📊", slides: "📽️", audio: "🎵", video: "🎞️", text: "📄", archive: "🗜️", other: "📎" };
const LABEL: Record<NextcloudActionId, string> = {
  download: "workspace.react.action.download",
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
  direct: ["download", "edit", "rename", "restore"],
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

  const docs = list.data ?? [];
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
  /** Un clic sur un fichier : l'éditeur en ligne s'il est modifiable, sinon le fichier dans le navigateur (affiché, pas téléchargé). */
  const openFile = (d: SyncDocument) => {
    if (d.editable) void openEditor(d);
    else if (userId) window.open(downloadUrl(userId, d, true));
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
        <aside style={{ flex: "0 0 260px", maxWidth: "100%" }}>
          <TreeNav tree="owner" highlight={false} keepOpen folders={folders.data ?? []}>
            {quota.data && <QuotaGauge quota={quota.data} />}
          </TreeNav>
          <NextcloudNav
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
        <main style={{ flex: "1 1 320px", minWidth: 0 }}>
          {link.data && !link.data.hidden && link.data.url && (
            <p data-nc-url>
              {t("nextcloud.url.connect")} : <a href={link.data.url} target="_blank" rel="noreferrer">{link.data.url}</a>
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
          {trash && docs.length > 0 && (
            <button type="button" className="btn btn-outline-danger mb-16" data-nc-action="empty-trash" onClick={() => userId && void run(() => nextcloudService.emptyTrash(userId))}>
              {t("workspace.empty.trash")}
            </button>
          )}
          <SelectionBar actions={actions} layout={LAYOUT} labels={LABEL} count={selection.length} attr={{ bar: "data-nc-action-bar", action: "data-nc-action" }} busy={busy} onAction={onAction} />
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
          {list.isSuccess && docs.length === 0 && <p data-nc-empty>{t("workspace.react.empty")}</p>}
          {list.isSuccess && docs.length > 0 && (
            <table className="table w-100" data-nc-list>
              <thead>
                <tr>
                  <th />
                  <th>{t("name")}</th>
                  <th>{t("owner")}</th>
                  <th>{t("modified")}</th>
                  <th>{t("size")}</th>
                </tr>
              </thead>
              <tbody>
                {docs.map((d) => (
                  <tr key={d.path} data-nc-item={d.isFolder ? "folder" : "file"} data-nc-path={d.path}>
                    <td>
                      <input type="checkbox" data-nc-select checked={selected.has(d.path)} onChange={() => toggle(d.path)} aria-label={d.name} />
                    </td>
                    <td>
                      <span aria-hidden="true">{ICON[d.isFolder ? "folder" : fileKind(d.contentType)]}</span> {trash ? d.name : open(d)}
                    </td>
                    <td />
                    <td>{parseDate(d.lastModified?.replace("T", " ").replace(/\.\d+Z?$/, "").replace(/(\d\d:\d\d):(\d\d)/, "$1.$2"))?.toLocaleDateString("fr-FR") ?? ""}</td>
                    <td>{d.isFolder ? "" : formatSize(d.size)}</td>
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
    </>
  );
};
