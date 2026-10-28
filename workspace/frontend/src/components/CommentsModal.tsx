import { Button, Modal } from "@open-ent/react";
import { FormEvent, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { parseDate } from "../lib/elements";
import { Rights } from "../lib/rights";
import { WorkspaceComment, WorkspaceElement } from "../lib/types";
import { canDeleteComment } from "../lib/versions";
import { commentService } from "../services/workspace.service";

/** Commentaires d'un document ou d'un dossier : lire, ajouter (droit de commenter), supprimer. */
export const CommentsModal = ({
  element,
  rights,
  userId,
  userName,
  onChanged,
  onClose,
}: {
  element: WorkspaceElement | null;
  rights: Rights;
  userId?: string;
  userName?: string;
  onChanged: () => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const [comments, setComments] = useState<WorkspaceComment[]>([]);
  const [text, setText] = useState("");
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setComments(element?.comments ?? []);
    setText("");
    setError(false);
  }, [element]);

  const add = async (e: FormEvent) => {
    e.preventDefault();
    if (!element || !text.trim()) return;
    setBusy(true);
    setError(false);
    try {
      const res = await commentService.add(element, text.trim());
      setComments((list) => [...list, { id: res.id, author: userId ?? "", authorName: userName, comment: text.trim(), posted: undefined }]);
      setText("");
      onChanged();
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  };
  const remove = async (c: WorkspaceComment) => {
    if (!element) return;
    setBusy(true);
    setError(false);
    try {
      await commentService.remove(element._id, c.id);
      setComments((list) => list.filter((x) => x.id !== c.id));
      onChanged();
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal id="workspace-comments" isOpen={!!element} onModalClose={onClose} scrollable>
      <Modal.Header onModalClose={onClose}>{t("workspace.react.action.comments")}</Modal.Header>
      <Modal.Body>
        {comments.length === 0 && <p data-comments-empty>{t("workspace.react.comments.empty")}</p>}
        <ul className="list-unstyled" data-comments-list>
          {comments.map((c) => (
            <li key={c.id} className="mb-12" data-comment={c.id}>
              <strong>{c.authorName}</strong> <small>{parseDate(c.posted)?.toLocaleString("fr-FR") ?? ""}</small>
              <p className="mb-4 text-break">{c.comment}</p>
              {canDeleteComment(c, rights, userId) && (
                <button type="button" className="btn btn-outline-danger btn-sm" disabled={busy} data-comment-delete onClick={() => remove(c)}>
                  {t("workspace.react.comments.delete")}
                </button>
              )}
            </li>
          ))}
        </ul>
        {error && (
          <p className="text-danger" role="alert" data-comments-error>
            {t("workspace.react.action.failed")}
          </p>
        )}
        {rights.comment && (
          <form onSubmit={add} data-comment-form>
            <label htmlFor="workspace-comment-text">{t("workspace.react.comments.add")}</label>
            <textarea id="workspace-comment-text" className="form-control" rows={3} value={text} onChange={(e) => setText(e.target.value)} />
            <Button type="submit" color="primary" className="mt-8" disabled={busy || !text.trim()} data-comment-submit>
              {t("workspace.react.comments.send")}
            </Button>
          </form>
        )}
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
          {t("workspace.react.close")}
        </Button>
      </Modal.Footer>
    </Modal>
  );
};
