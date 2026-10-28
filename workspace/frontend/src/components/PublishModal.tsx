import { Button, Modal } from "@open-ent/react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { WorkspaceElement } from "../lib/types";
import { elementActions } from "../services/workspace.service";

/** Publier un document sur le portail public (réservé aux administrateurs ; le serveur contrôle aussi). */
export const PublishModal = ({
  element,
  published = false,
  onChanged,
  onClose,
}: {
  element: WorkspaceElement | null;
  /** Le document est déjà publié (la liste ordinaire ne l'indique pas : l'état vient de la liste des publications). */
  published?: boolean;
  onChanged: () => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const [url, setUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    setUrl(element && (element.portalPublication || published) ? `/workspace/pub/document/${element._id}` : null);
    setFailed(false);
  }, [element, published]);

  const run = async (publish: boolean) => {
    if (!element) return;
    setBusy(true);
    setFailed(false);
    try {
      if (publish) {
        const res = await elementActions.publish(element._id);
        setUrl(res?.url ?? `/workspace/pub/document/${element._id}`);
      } else {
        await elementActions.unpublish(element._id);
        setUrl(null);
      }
      onChanged();
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal id="workspace-publish" isOpen={!!element} onModalClose={onClose}>
      <Modal.Header onModalClose={onClose}>{t("workspace.document.publish")}</Modal.Header>
      <Modal.Body>
        {url ? (
          <>
            <p>{t("workspace.document.publish.published")}</p>
            <input className="form-control" readOnly value={new URL(url, window.location.origin).toString()} onFocus={(e) => e.target.select()} data-publish-url />
          </>
        ) : (
          <p>{t("workspace.document.publish.description")}</p>
        )}
        {failed && (
          <p className="text-danger mt-8" role="alert" data-publish-error>
            {t("workspace.document.publish.error")}
          </p>
        )}
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
          {t("cancel")}
        </Button>
        {url ? (
          <Button type="button" color="danger" disabled={busy} onClick={() => run(false)} data-unpublish>
            {t("workspace.document.publish.remove")}
          </Button>
        ) : (
          <Button type="button" color="primary" disabled={busy} onClick={() => run(true)} data-publish>
            {t("workspace.document.publish")}
          </Button>
        )}
      </Modal.Footer>
    </Modal>
  );
};
