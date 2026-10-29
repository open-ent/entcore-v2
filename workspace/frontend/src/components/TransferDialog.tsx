import { Button, Modal } from "@open-ent/react";
import { ReactNode, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { forbiddenTargets } from "../lib/actions";
import { destinations, directionOf, errorKeyOf, impactKeys, transferKeys, TransferMode } from "../lib/transfer";
import { TreeName, WorkspaceElement } from "../lib/types";
import { elementActions } from "../services/workspace.service";

interface Dest {
  /** `null` = racine de « Mes documents ». */
  id: string | null;
  shared: boolean;
}

type Step = "pick" | "confirm" | "processing" | "finished" | "error";

/** Phrases des traductions avec leurs rares balises (<b>, <br/>), rendues sans injecter de HTML. */
export const rich = (text: string): ReactNode[] =>
  text.split(/(<br\s*\/?>|<\/?b>)/i).reduce<{ nodes: ReactNode[]; bold: boolean }>(
    (acc, part, i) => {
      if (/^<br/i.test(part)) acc.nodes.push(<br key={i} />);
      else if (/^<b>$/i.test(part)) acc.bold = true;
      else if (/^<\/b>$/i.test(part)) acc.bold = false;
      else if (part) acc.nodes.push(acc.bold ? <strong key={i}>{part}</strong> : <span key={i}>{part}</span>);
      return acc;
    },
    { nodes: [], bold: false },
  ).nodes;

const Branch = ({
  folders,
  roots,
  forbidden,
  chosen,
  shared,
  onChoose,
}: {
  folders: WorkspaceElement[];
  /** Dossiers de départ (les autres s'en déduisent par parenté). */
  roots: WorkspaceElement[];
  forbidden: Set<string>;
  chosen: Dest | null | undefined;
  shared: boolean;
  onChoose: (d: Dest) => void;
}) => (
  <ul className="list-unstyled ps-16">
    {roots.map((f) => (
      <li key={f._id}>
        <button
          type="button"
          className="btn btn-outline-secondary btn-sm"
          disabled={forbidden.has(f._id)}
          aria-pressed={chosen?.id === f._id}
          data-picker-folder={f._id}
          onClick={() => onChoose({ id: f._id, shared })}
        >
          {chosen?.id === f._id ? "✔ " : ""}
          {f.name}
        </button>
        {!forbidden.has(f._id) && <Branch folders={folders} roots={folders.filter((c) => c.eParent === f._id)} forbidden={forbidden} chosen={chosen} shared={shared} onChoose={onChoose} />}
      </li>
    ))}
  </ul>
);

/**
 * Copie ou déplacement : choix de la destination (« Mes documents » et les dossiers partagés où l'on peut contribuer), avertissement
 * quand un déplacement change d'espace de partage (déplacer, ou copier à la place), progression, puis confirmation de fin.
 * Mêmes étapes et mêmes messages que l'ancienne interface.
 */
export const TransferDialog = ({
  mode,
  elements,
  folders,
  userId,
  groupIds,
  fromTree,
  onClose,
  onDone,
}: {
  mode: TransferMode;
  elements: WorkspaceElement[];
  folders: WorkspaceElement[];
  userId?: string;
  groupIds: string[];
  fromTree: TreeName;
  onClose: () => void;
  onDone: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const [step, setStep] = useState<Step>("pick");
  const [chosen, setChosen] = useState<Dest | null | undefined>(undefined);
  const [errorKey, setErrorKey] = useState("workspace.react.action.failed");
  const [doing, setDoing] = useState<TransferMode>(mode);
  const keys = transferKeys(doing);
  const dest = useMemo(() => destinations(folders, userId, groupIds), [folders, userId, groupIds]);
  const forbidden = useMemo(() => forbiddenTargets(folders, elements), [folders, elements]);
  const personalIds = new Set(dest.personal.map((f) => f._id));
  const sharedIds = new Set(dest.shared.map((f) => f._id));
  const direction = chosen ? directionOf(elements, chosen.shared) : "nope";
  const mine = elements.every((e) => e.owner === userId);

  const execute = async (kind: TransferMode) => {
    if (!chosen) return;
    setDoing(kind);
    setStep("processing");
    const destId = chosen.id ?? undefined;
    try {
      if (kind === "copy") await elementActions.copy(elements, destId);
      else if (fromTree === "shared" && direction === "toown") await elementActions.moveForShared(elements, destId);
      else await elementActions.move(elements, destId);
      setStep("finished");
      window.setTimeout(onDone, 1000);
    } catch (e) {
      setErrorKey(errorKeyOf(e));
      setStep("error");
    }
  };

  const submit = () => {
    if (!chosen) return;
    if (mode === "copy" || direction === "nope") void execute(mode);
    else setStep("confirm");
  };

  const impact = direction === "nope" ? null : impactKeys(direction, mine);
  return (
    <Modal id="workspace-transfer" isOpen onModalClose={onClose} scrollable>
      <Modal.Header onModalClose={onClose}>{step === "confirm" && impact ? t(impact.title) : t(keys.title)}</Modal.Header>
      <Modal.Body>
        {step === "pick" && (
          <>
            <p>{t(keys.info)}</p>
            <button type="button" className="btn btn-outline-secondary btn-sm" aria-pressed={chosen?.id === null} data-picker-root onClick={() => setChosen({ id: null, shared: false })}>
              {chosen?.id === null ? "✔ " : ""}
              {t("documents")}
            </button>
            <Branch folders={dest.personal} roots={dest.personal.filter((f) => !f.eParent || !personalIds.has(f.eParent))} forbidden={forbidden} chosen={chosen} shared={false} onChoose={setChosen} />
            {dest.shared.length > 0 && (
              <>
                <p className="mt-16 mb-4">
                  <strong>{t("shared_tree")}</strong>
                </p>
                <Branch folders={dest.shared} roots={dest.shared.filter((f) => !f.eParent || !sharedIds.has(f.eParent))} forbidden={forbidden} chosen={chosen} shared onChoose={setChosen} />
              </>
            )}
          </>
        )}
        {step === "confirm" && impact && (
          <div data-transfer-impact>
            {impact.messages.map((m) => (
              <p key={m}>{rich(t(m))}</p>
            ))}
          </div>
        )}
        {step === "processing" && (
          <p role="status" data-transfer-processing>
            {t(keys.processing)}
          </p>
        )}
        {step === "finished" && (
          <p role="status" style={{ color: "#2a9d3d", fontWeight: 600 }} data-transfer-finished>
            {t(keys.finished)}
          </p>
        )}
        {step === "error" && (
          <p role="alert" className="text-danger" data-transfer-error>
            {t(errorKey)}
          </p>
        )}
      </Modal.Body>
      <Modal.Footer>
        {step === "pick" && (
          <>
            <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
              {t("cancel")}
            </Button>
            <Button type="button" color="primary" disabled={chosen === undefined} onClick={submit} data-picker-confirm>
              {t(keys.action)}
            </Button>
          </>
        )}
        {step === "confirm" && (
          <>
            <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
              {t("cancel")}
            </Button>
            <Button type="button" color="secondary" variant="outline" onClick={() => void execute("copy")} data-transfer-copy>
              {t("workspace.move.window.docopy")}
            </Button>
            <Button type="button" color="primary" onClick={() => void execute("move")} data-transfer-move>
              {t("workspace.move.window.action")}
            </Button>
          </>
        )}
        {step === "error" && (
          <Button type="button" color="primary" onClick={onClose}>
            {t("cancel")}
          </Button>
        )}
      </Modal.Footer>
    </Modal>
  );
};
