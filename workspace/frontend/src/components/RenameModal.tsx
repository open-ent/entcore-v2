import { Button, Modal } from "@open-ent/react";
import { FormEvent, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { renamedWithExtension } from "../lib/actions";
import { cleanName, isForbiddenChar, managersOf, MAX_NAME } from "../lib/properties";
import { folderNameError } from "../lib/upload";
import { WorkspaceElement } from "../lib/types";
import { useParentInfo } from "../services/queries";

/**
 * Propriétés d'un document ou d'un dossier : dossier parent et ses gestionnaires, puis le nom (80 caractères au plus, sans / \ < > |).
 * L'extension d'un fichier est conservée.
 */
export const RenameModal = ({
  element,
  onRename,
  onClose,
  error,
  parentHref,
}: {
  element: WorkspaceElement | null;
  onRename: (name: string) => void;
  onClose: () => void;
  error?: string | null;
  /** Adresse (dans l'application) du dossier parent, sinon seul son nom est affiché. */
  parentHref?: (parentId: string) => string;
}) => {
  const { t } = useTranslation("workspace");
  const [name, setName] = useState("");
  const [local, setLocal] = useState<string | null>(null);
  const parent = useParentInfo(element?.eParent ?? undefined, !!element);
  useEffect(() => {
    setName(element?.name ?? "");
    setLocal(null);
  }, [element]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!element) return;
    const cleaned = cleanName(name);
    const invalid = element.eType === "folder" ? folderNameError(cleaned) : cleaned ? null : "empty";
    if (invalid) {
      setLocal(invalid === "underscore" ? t("workspace.folder.invalid.name") : t("workspace.invalid.folder.name"));
      return;
    }
    onRename(renamedWithExtension(element, cleaned));
  };

  const managers = managersOf(parent.data);
  return (
    <Modal id="workspace-rename" isOpen={!!element} onModalClose={onClose} focusId="workspace-rename-name">
      <Modal.Header onModalClose={onClose}>{t("workspace.properties")}</Modal.Header>
      <form onSubmit={submit} data-rename-form>
        <Modal.Body>
          {parent.data && (
            <div className="mb-16" data-properties-parent>
              <strong>{t("workspace.properties.parent")}</strong> :{" "}
              {parentHref ? <a href={parentHref(parent.data._id)}>{parent.data.name}</a> : <span>{parent.data.name}</span>}
              <div className="mt-8">{t("workspace.properties.managers")} :</div>
              <ul className="mb-0" data-properties-managers>
                {managers.map((m) => (
                  <li key={m.id}>
                    <a href={m.uri} target="_blank" rel="noreferrer">
                      {m.name}
                    </a>
                  </li>
                ))}
              </ul>
            </div>
          )}
          <label htmlFor="workspace-rename-name">{t(element?.eType === "folder" ? "workspace.folder.name" : "workspace.document.name")}</label>
          <input
            id="workspace-rename-name"
            className="form-control"
            value={name}
            maxLength={MAX_NAME}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (isForbiddenChar(e.key)) e.preventDefault();
            }}
            autoComplete="off"
          />
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
            {t("workspace.react.save")}
          </Button>
        </Modal.Footer>
      </form>
    </Modal>
  );
};
