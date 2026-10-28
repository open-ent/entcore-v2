import { Button, Modal } from "@open-ent/react";
import { useTranslation } from "react-i18next";
import { WorkspaceElement } from "../lib/types";

/**
 * Avant de partager depuis « Mes documents » : le partage déplace l'élément dans « Documents partagés ».
 * On peut le partager tel quel, ou en partager une copie pour garder l'original dans ses documents.
 */
export const ShareWarning = ({
  element,
  busy,
  onShare,
  onShareCopy,
  onClose,
}: {
  element: WorkspaceElement | null;
  busy: boolean;
  onShare: () => void;
  onShareCopy: () => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const folder = element?.eType === "folder";
  return (
    <Modal id="workspace-share-warning" isOpen={!!element} onModalClose={onClose}>
      <Modal.Header onModalClose={onClose}>{t(folder ? "workspace.folders.warning.title" : "workspace.files.warning.title")}</Modal.Header>
      <Modal.Body>
        {busy ? (
          <p>{t("workspace.copy.window.processing")}</p>
        ) : (
          <div dangerouslySetInnerHTML={{ __html: t(folder ? "workspace.folders.warning.content" : "workspace.files.warning.content") }} />
        )}
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" color="tertiary" variant="ghost" onClick={onClose} disabled={busy}>
          {t("workspace.warning.cancel")}
        </Button>
        <Button type="button" color="secondary" variant="outline" onClick={onShareCopy} disabled={busy} data-share-copy>
          {t("workspace.warning.copy")}
        </Button>
        <Button type="button" color="primary" onClick={onShare} disabled={busy} data-share-nocopy>
          {t("workspace.warning.notcopy")}
        </Button>
      </Modal.Footer>
    </Modal>
  );
};
