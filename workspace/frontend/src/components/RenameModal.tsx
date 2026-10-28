import { Button, Modal } from "@open-ent/react";
import { FormEvent, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { renamedWithExtension } from "../lib/actions";
import { folderNameError } from "../lib/upload";
import { WorkspaceElement } from "../lib/types";

/** Renommer un document ou un dossier ; l'extension d'un fichier est conservée. */
export const RenameModal = ({
  element,
  onRename,
  onClose,
  error,
}: {
  element: WorkspaceElement | null;
  onRename: (name: string) => void;
  onClose: () => void;
  error?: string | null;
}) => {
  const { t } = useTranslation("workspace");
  const [name, setName] = useState("");
  const [local, setLocal] = useState<string | null>(null);
  useEffect(() => {
    setName(element?.name ?? "");
    setLocal(null);
  }, [element]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!element) return;
    const invalid = element.eType === "folder" ? folderNameError(name) : name.trim() ? null : "empty";
    if (invalid) {
      setLocal(invalid === "underscore" ? t("workspace.folder.invalid.name") : t("workspace.invalid.folder.name"));
      return;
    }
    onRename(renamedWithExtension(element, name));
  };

  return (
    <Modal id="workspace-rename" isOpen={!!element} onModalClose={onClose} focusId="workspace-rename-name">
      <Modal.Header onModalClose={onClose}>{t("workspace.rename")}</Modal.Header>
      <form onSubmit={submit} data-rename-form>
        <Modal.Body>
          <label htmlFor="workspace-rename-name">{t("name")}</label>
          <input id="workspace-rename-name" className="form-control" value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />
          {(local || error) && (
            <p className="text-danger mt-8" role="alert" data-rename-error>
              {local ?? error}
            </p>
          )}
        </Modal.Body>
        <Modal.Footer>
          <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
            {t("cancel")}
          </Button>
          <Button type="submit" color="primary" data-rename-submit>
            {t("workspace.rename")}
          </Button>
        </Modal.Footer>
      </form>
    </Modal>
  );
};
