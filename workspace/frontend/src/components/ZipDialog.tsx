import { Button, Modal } from "@open-ent/react";
import { useTranslation } from "react-i18next";

/** Archive déposée : la décompresser dans le dossier, ou la conserver telle quelle. */
export const ZipDialog = ({
  file,
  onChoose,
  onClose,
}: {
  file: File | null;
  onChoose: (uncompress: boolean) => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  return (
    <Modal id="workspace-zip-dialog" isOpen={!!file} onModalClose={onClose}>
      <Modal.Header onModalClose={onClose}>{t("workspace.import.zip.title")}</Modal.Header>
      <Modal.Body>
        <p>{file?.name}</p>
        <p dangerouslySetInnerHTML={{ __html: t("workspace.import.zip.content") }} />
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
          {t("workspace.import.zip.cancel")}
        </Button>
        <Button type="button" color="secondary" variant="outline" onClick={() => onChoose(false)} data-zip-keep>
          {t("workspace.import.zip.upload")}
        </Button>
        <Button type="button" color="primary" onClick={() => onChoose(true)} data-zip-uncompress>
          {t("workspace.import.zip.uncompress")}
        </Button>
      </Modal.Footer>
    </Modal>
  );
};
