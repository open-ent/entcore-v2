import { Button, Modal } from "@open-ent/react";
import { useQueryClient } from "@tanstack/react-query";
import { FormEvent, useState } from "react";
import { useTranslation } from "react-i18next";
import { folderNameError } from "../lib/upload";
import { createFolder } from "../services/workspace.service";

/** Fenêtre « Créer un dossier ». Les erreurs du serveur (doublon, nom invalide) sont traduites et gardées dans la fenêtre. */
export const NewFolderModal = ({ parentId, isOpen, onClose }: { parentId?: string; isOpen: boolean; onClose: () => void }) => {
  const { t } = useTranslation("workspace");
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const invalid = folderNameError(name);
    if (invalid) {
      setError(invalid === "underscore" ? t("workspace.folder.invalid.name") : t("workspace.invalid.folder.name"));
      return;
    }
    setBusy(true);
    try {
      await createFolder(name.trim(), parentId);
      await Promise.all([
        qc.invalidateQueries({ queryKey: ["workspace", "content"] }),
        qc.invalidateQueries({ queryKey: ["workspace", "folders"] }),
      ]);
      setName("");
      setError(null);
      onClose();
    } catch (err) {
      const code = typeof err === "string" ? err : (err as { message?: string })?.message;
      setError(code && /^[\w-]+(\.[\w-]+)+$/.test(code) ? t(code) : t("workspace.react.folder.error"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal id="workspace-new-folder" isOpen={isOpen} onModalClose={onClose} focusId="workspace-new-folder-name">
      <Modal.Header onModalClose={onClose}>{t("folder.new.title")}</Modal.Header>
      <form onSubmit={submit} data-new-folder-form>
        <Modal.Body>
          <label htmlFor="workspace-new-folder-name">{t("workspace.folder.name")}</label>
          <input
            id="workspace-new-folder-name"
            className="form-control"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoComplete="off"
          />
          {error && (
            <p className="text-danger mt-8" role="alert" data-new-folder-error>
              {error}
            </p>
          )}
        </Modal.Body>
        <Modal.Footer>
          <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
            {t("cancel")}
          </Button>
          <Button type="submit" color="primary" disabled={busy} data-new-folder-submit>
            {t("workspace.folder.create")}
          </Button>
        </Modal.Footer>
      </form>
    </Modal>
  );
};
