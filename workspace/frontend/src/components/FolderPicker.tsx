import { Button, Modal } from "@open-ent/react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { childFolders } from "../lib/elements";
import { canExpand } from "../lib/lazyFolders";
import type { LazyProps } from "./TreeNav";
import { WorkspaceElement } from "../lib/types";

const Branch = ({
  folders,
  parentId,
  forbidden,
  chosen,
  onChoose,
  lazy,
}: {
  folders: WorkspaceElement[];
  parentId: string | null;
  forbidden: Set<string>;
  chosen: string | null | undefined;
  onChoose: (id: string) => void;
  lazy?: LazyProps;
}) => {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  return (
    <ul className="list-unstyled ps-16">
      {childFolders(folders, parentId).map((f) => (
        <li key={f._id}>
          {lazy &&
            canExpand(
              childFolders(folders, f._id).length > 0,
              true,
              lazy.isLoaded(f._id),
            ) && (
              <button
                type="button"
                className="btn btn-link p-0 me-8"
                aria-expanded={!!open[f._id]}
                data-picker-toggle={f._id}
                onClick={() => {
                  if (!open[f._id]) lazy.onExpand(f._id);
                  setOpen((o) => ({ ...o, [f._id]: !o[f._id] }));
                }}
              >
                {open[f._id] ? "▾" : "▸"}
              </button>
            )}
          <button
            type="button"
            className="btn btn-outline-secondary btn-sm"
            disabled={forbidden.has(f._id)}
            aria-pressed={chosen === f._id}
            data-picker-folder={f._id}
            onClick={() => onChoose(f._id)}
          >
            {chosen === f._id ? "✔ " : ""}
            {f.name}
          </button>
          {!forbidden.has(f._id) && (!lazy || open[f._id]) && (
            <Branch
              folders={folders}
              parentId={f._id}
              forbidden={forbidden}
              chosen={chosen}
              onChoose={onChoose}
              lazy={lazy}
            />
          )}
        </li>
      ))}
    </ul>
  );
};

/** Choix du dossier de destination (copie ou déplacement) dans « Mes documents » ; `null` = racine. */
export const FolderPicker = ({
  isOpen,
  title,
  folders,
  forbidden,
  lazy,
  onPick,
  onClose,
}: {
  isOpen: boolean;
  title: string;
  folders: WorkspaceElement[];
  forbidden: Set<string>;
  /** Mode « lazy » : sous-dossiers chargés au dépliage. */
  lazy?: LazyProps;
  onPick: (destId: string | null) => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const [chosen, setChosen] = useState<string | null | undefined>(undefined);
  return (
    <Modal
      id="workspace-folder-picker"
      isOpen={isOpen}
      onModalClose={onClose}
      scrollable
    >
      <Modal.Header onModalClose={onClose}>{title}</Modal.Header>
      <Modal.Body>
        <button
          type="button"
          className="btn btn-outline-secondary btn-sm"
          aria-pressed={chosen === null}
          data-picker-root
          onClick={() => setChosen(null)}
        >
          {chosen === null ? "✔ " : ""}
          {t("documents")}
        </button>
        <Branch
          folders={folders}
          parentId={null}
          forbidden={forbidden}
          chosen={chosen}
          onChoose={setChosen}
          lazy={lazy}
        />
      </Modal.Body>
      <Modal.Footer>
        <Button
          type="button"
          color="tertiary"
          variant="ghost"
          onClick={onClose}
        >
          {t("cancel")}
        </Button>
        <Button
          type="button"
          color="primary"
          disabled={chosen === undefined}
          onClick={() => chosen !== undefined && onPick(chosen)}
          data-picker-confirm
        >
          {title}
        </Button>
      </Modal.Footer>
    </Modal>
  );
};
