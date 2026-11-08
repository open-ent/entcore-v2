import { Button, ImageEditor, LoadingScreen, Modal, useEdificeClient } from "@open-ent/react";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { readFlags } from "../lib/flags";
import {
  canOpenInGeogebra,
  canOpenInLool,
  canOpenInScratch,
  geogebraUrl,
  LOOL_OPEN,
  loolUrl,
  scratchUrl,
} from "../lib/integrations";
import { rightsOn } from "../lib/rights";
import { previewKind } from "../lib/viewer";
import { useElement, useLoolContext } from "../services/queries";
import { updateImage } from "../services/workspace.service";
import { Content } from "./ViewerContent";

const NO_RIGHTS = { read: false, contrib: false, manager: false, comment: false };

/**
 * Visionneuse d'un document, en fenêtre : le contenu s'affiche selon son type, on passe au document précédent ou suivant
 * du dossier sans fermer, on télécharge, on passe en plein écran, et on retouche une image quand on en a le droit.
 * La liste reste en arrière-plan : on ne perd pas sa place.
 */
export const ViewerModal = ({
  id,
  siblings = [],
  editImage = false,
  onClose,
  onCurrentChange,
}: {
  /** Document affiché ; absent = fenêtre fermée. */
  id?: string;
  /** Documents du même emplacement, pour « précédent » et « suivant ». */
  siblings?: string[];
  /** Ouvre directement la retouche d'image (action « Retoucher l'image » de la liste). */
  editImage?: boolean;
  onClose: () => void;
  /** Le document affiché a changé (la page `/view/:id` s'en sert pour suivre dans l'adresse). */
  onCurrentChange?: (id: string) => void;
}) => {
  const { t } = useTranslation("workspace");
  const [current, setCurrent] = useState<string | undefined>(id);
  useEffect(() => setCurrent(id), [id]);
  const element = useElement(current);
  const box = useRef<HTMLDivElement>(null);
  const qc = useQueryClient();
  const { user } = useEdificeClient();
  const flags = useMemo(() => readFlags(), []);
  const workflows = new Set(
    ((user as { authorizedActions?: Array<{ name: string }> } | undefined)?.authorizedActions ?? []).map((a) => a.name),
  );
  const lool = useLoolContext(flags.enableLool && workflows.has(LOOL_OPEN));
  const [editing, setEditing] = useState(editImage);
  useEffect(() => setEditing(editImage), [editImage, id]);
  const [editError, setEditError] = useState(false);

  const index = current ? siblings.indexOf(current) : -1;
  const prev = index > 0 ? siblings[index - 1] : undefined;
  const next = index >= 0 && index < siblings.length - 1 ? siblings[index + 1] : undefined;
  const go = (target?: string) => {
    if (!target) return;
    setCurrent(target);
    onCurrentChange?.(target);
  };
  // flèches ← → : document précédent et suivant, comme dans l'ancienne interface
  const arrows = useRef<(e: KeyboardEvent) => void>(() => undefined);
  arrows.current = (e) => {
    if (e.key === "ArrowLeft") go(prev);
    if (e.key === "ArrowRight") go(next);
  };
  useEffect(() => {
    if (!id) return;
    const listener = (e: KeyboardEvent) => arrows.current(e);
    window.addEventListener("keydown", listener);
    return () => window.removeEventListener("keydown", listener);
  }, [id]);

  if (!id) return null;
  const el = element.data;
  const rights = el ? rightsOn(el, user?.userId, (user as { groupsIds?: string[] } | undefined)?.groupsIds ?? []) : NO_RIGHTS;
  const kind = el ? previewKind(el.metadata?.["content-type"], el.name) : "none";
  return (
    <>
      <Modal id="workspace-viewer" isOpen size="lg" scrollable onModalClose={onClose}>
        <Modal.Header onModalClose={onClose}>
          <span data-viewer-title>{el?.name ?? t("workspace.title")}</span>
        </Modal.Header>
        <Modal.Body>
          {element.isLoading && <LoadingScreen />}
          {(element.isError || (!element.isLoading && !el)) && (
            <p className="text-danger" role="alert" data-viewer-error>
              {t("workspace.react.viewer.unavailable")}
            </p>
          )}
          {editError && (
            <p className="text-danger" role="alert" data-viewer-edit-error>
              {t("workspace.react.action.failed")}
            </p>
          )}
          {el && (
            <div ref={box} className="ws-viewer-body">
              <Content element={el} />
            </div>
          )}
          {el && (
            <p className="text-gray-700 mt-8 mb-0">
              <small>
                {t("owner")} : <strong>{el.ownerName}</strong>
              </small>
            </p>
          )}
        </Modal.Body>
        <Modal.Footer>
          <div className="d-flex flex-wrap gap-8 align-items-center w-100" data-viewer-toolbar>
            <Button type="button" color="tertiary" variant="ghost" onClick={onClose} data-viewer-back>
              {t("workspace.react.viewer.back")}
            </Button>
            {siblings.length > 1 && (
              <>
                <Button type="button" color="tertiary" variant="outline" disabled={!prev} onClick={() => go(prev)} data-viewer-prev>
                  {t("workspace.react.viewer.previous")}
                </Button>
                <Button type="button" color="tertiary" variant="outline" disabled={!next} onClick={() => go(next)} data-viewer-next>
                  {t("workspace.react.viewer.next")}
                </Button>
              </>
            )}
            <span className="ms-auto" />
            <Button type="button" color="tertiary" variant="ghost" onClick={() => box.current?.requestFullscreen?.()} data-viewer-fullscreen>
              {t("workspace.react.viewer.fullscreen")}
            </Button>
            {el && rights.contrib && kind === "image" && (
              <Button type="button" color="tertiary" variant="ghost" onClick={() => setEditing(true)} data-viewer-edit-image>
                {t("workspace.react.edit.image")}
              </Button>
            )}
            {el && flags.enableLool && workflows.has(LOOL_OPEN) && canOpenInLool(lool.data, el) && (
              <Button type="button" color="tertiary" variant="ghost" onClick={() => window.open(loolUrl(el._id))} data-viewer-open-lool>
                {t("workspace.external.open")}
              </Button>
            )}
            {el && flags.enableScratch && canOpenInScratch(el) && (
              <Button type="button" color="tertiary" variant="ghost" onClick={() => window.open(scratchUrl(el._id))} data-viewer-open-scratch>
                {t("workspace.external.open")}
              </Button>
            )}
            {el && flags.enableGeogebra && canOpenInGeogebra(el) && (
              <Button type="button" color="tertiary" variant="ghost" onClick={() => window.open(geogebraUrl(el))} data-viewer-open-geogebra>
                {t("workspace.external.open")}
              </Button>
            )}
            {el && (
              <a className="btn btn-filled btn-primary" href={`/workspace/document/${el._id}`} data-viewer-download>
                {t("workspace.download")}
              </a>
            )}
          </div>
        </Modal.Footer>
      </Modal>
      {editing && el && rights.contrib && (
        <ImageEditor
          isOpen
          image={`/workspace/document/${el._id}`}
          onCancel={() => setEditing(false)}
          onError={() => setEditError(true)}
          onSave={async ({ blob, legend, altText }) => {
            try {
              setEditError(false);
              await updateImage(el._id, blob, el.name, altText, legend);
              setEditing(false);
              await Promise.all(["element", "content"].map((k) => qc.invalidateQueries({ queryKey: ["workspace", k] })));
            } catch {
              setEditError(true);
            }
          }}
        />
      )}
    </>
  );
};
