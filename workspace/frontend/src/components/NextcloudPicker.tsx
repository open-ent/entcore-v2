import { Button, Modal } from "@open-ent/react";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { HIDDEN_FOLDER, parentPath } from "../lib/nextcloud";
import { nextcloudService } from "../services/nextcloud.service";

/** Choix d'un dossier NextCloud de destination (les dossiers de l'espace documentaire ne conviennent pas ici). */
export const NextcloudPicker = ({
  isOpen,
  userId,
  title,
  onPick,
  onClose,
}: {
  isOpen: boolean;
  userId?: string;
  title: string;
  onPick: (path: string) => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const [path, setPath] = useState("");
  const folders = useQuery({
    queryKey: ["workspace", "nextcloud-picker", userId, path],
    queryFn: async () => (await nextcloudService.list(userId as string, path)).filter((d) => d.isFolder && !HIDDEN_FOLDER.test(d.name)),
    enabled: isOpen && !!userId,
  });
  return (
    <Modal id="workspace-nextcloud-picker" isOpen={isOpen} onModalClose={onClose} scrollable>
      <Modal.Header onModalClose={onClose}>{title}</Modal.Header>
      <Modal.Body>
        <p data-nc-picker-path>/{path}</p>
        {path && (
          <button type="button" className="btn btn-outline-secondary btn-sm mb-8" onClick={() => setPath(parentPath(path))} data-nc-picker-up>
            ↑ {t("workspace.react.nc.up")}
          </button>
        )}
        {folders.isError && (
          <p className="text-danger" role="alert">
            {t("nextcloud.share.picker.error")}
          </p>
        )}
        {folders.isSuccess && folders.data.length === 0 && <p>{t("nextcloud.share.picker.empty")}</p>}
        <ul className="list-unstyled">
          {(folders.data ?? []).map((f) => (
            <li key={f.path}>
              <button type="button" className="btn btn-outline-secondary btn-sm" data-nc-picker-folder={f.path} onClick={() => setPath(f.path)}>
                📁 {f.name}
              </button>
            </li>
          ))}
        </ul>
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
          {t("nextcloud.share.cancel")}
        </Button>
        <Button type="button" color="primary" onClick={() => onPick(path)} data-nc-picker-confirm>
          {t("nextcloud.share.confirm")}
        </Button>
      </Modal.Footer>
    </Modal>
  );
};
