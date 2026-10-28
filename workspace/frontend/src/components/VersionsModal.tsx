import { Button, Modal } from "@open-ent/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChangeEvent, useState } from "react";
import { useTranslation } from "react-i18next";
import { formatSize, parseDate } from "../lib/elements";
import { Rights } from "../lib/rights";
import { WorkspaceElement } from "../lib/types";
import { canDeleteRevision, newestFirst } from "../lib/versions";
import { revisionService } from "../services/workspace.service";

/** Historique des versions d'un document : télécharger une ancienne version, en ajouter une, supprimer. */
export const VersionsModal = ({
  element,
  rights,
  userId,
  onChanged,
  onClose,
}: {
  element: WorkspaceElement | null;
  rights: Rights;
  userId?: string;
  onChanged: () => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const key = ["workspace", "revisions", element?._id];
  const revisions = useQuery({ queryKey: key, queryFn: () => revisionService.list(element!._id), enabled: !!element });

  const refresh = async () => {
    await qc.invalidateQueries({ queryKey: key });
    onChanged();
  };
  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await refresh();
    } catch (e) {
      setError(typeof e === "string" && e.includes(".") ? t(e) : t("workspace.react.action.failed"));
    } finally {
      setBusy(false);
    }
  };
  const list = newestFirst(revisions.data ?? []);

  return (
    <Modal id="workspace-versions" isOpen={!!element} onModalClose={onClose} size="lg" scrollable>
      <Modal.Header onModalClose={onClose}>{t("workspace.versions.list")}</Modal.Header>
      <Modal.Body>
        {rights.contrib && (
          <div className="mb-16">
            <label className="btn btn-outline-primary">
              {t("workspace.add.version")}
              <input
                type="file"
                hidden
                data-version-input
                disabled={busy}
                onChange={(e: ChangeEvent<HTMLInputElement>) => {
                  const file = e.target.files?.[0];
                  e.target.value = "";
                  if (file && element) void run(() => revisionService.add(element._id, file));
                }}
              />
            </label>
          </div>
        )}
        {revisions.isLoading && <p>…</p>}
        {revisions.isError && (
          <p className="text-danger" role="alert" data-versions-error>
            {t("workspace.react.action.failed")}
          </p>
        )}
        {error && (
          <p className="text-danger" role="alert" data-versions-error>
            {error}
          </p>
        )}
        <table className="table w-100" data-versions-list>
          <thead>
            <tr>
              <th>{t("name")}</th>
              <th>{t("user")}</th>
              <th>{t("modified")}</th>
              <th>{t("size")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {list.map((r) => (
              <tr key={r._id} data-version={r._id}>
                <td>
                  <a href={`/workspace/document/${r.documentId}/revision/${r._id}`}>{r.name}</a>
                </td>
                <td>{r.userName}</td>
                <td>{parseDate(r.date?.$date?.replace("T", " ").replace(/\.\d+Z$/, "").replace(/(\d\d:\d\d):(\d\d)/, "$1.$2"))?.toLocaleString("fr-FR") ?? r.date?.$date}</td>
                <td>{formatSize(r.metadata?.size)}</td>
                <td>
                  {canDeleteRevision(r, rights, userId, list.length) && (
                    <button type="button" className="btn btn-link text-danger p-0" disabled={busy} data-version-delete onClick={() => run(() => revisionService.remove(r.documentId, r._id))}>
                      {t("workspace.delete.version")}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
          {t("workspace.react.close")}
        </Button>
      </Modal.Footer>
    </Modal>
  );
};
