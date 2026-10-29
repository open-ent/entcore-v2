import { act, fireEvent, render } from "@testing-library/react";
import { TransferDialog } from "./TransferDialog";
import { WorkspaceElement } from "../lib/types";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock("@open-ent/react", () => {
  const Modal = ({ children }: { children: unknown }) => <div data-modal>{children as never}</div>;
  Modal.Header = ({ children }: { children: unknown }) => <h2>{children as never}</h2>;
  Modal.Body = ({ children }: { children: unknown }) => <div>{children as never}</div>;
  Modal.Footer = ({ children }: { children: unknown }) => <div>{children as never}</div>;
  const Button = ({ children, onClick, disabled, ...rest }: Record<string, unknown>) => (
    <button type="button" onClick={onClick as never} disabled={disabled as boolean} {...(rest as object)}>
      {children as never}
    </button>
  );
  return { Modal, Button };
});
const svc = vi.hoisted(() => ({ copy: vi.fn(), move: vi.fn(), moveForShared: vi.fn() }));
vi.mock("../services/workspace.service", () => ({ elementActions: svc }));

const PREFIX = "org-entcore-workspace-controllers-WorkspaceController|";
const el = (p: Partial<WorkspaceElement>): WorkspaceElement => ({ _id: "x", name: "n", eType: "file", eParent: null, owner: "u1", ...p });
const perso = el({ _id: "doc", name: "mon.txt" });
const partage = el({ _id: "p1", name: "partage.txt", owner: "u2", shared: [{ userId: "u1", [PREFIX + "shareJson"]: true }] });
const dossierPerso = el({ _id: "fp", name: "Perso", eType: "folder" });
const dossierPartage = el({ _id: "fs", name: "Equipe", eType: "folder", owner: "u2", shared: [{ userId: "u1", [PREFIX + "updateDocument"]: true }] });
const folders = [dossierPerso, dossierPartage];

const show = (props: Partial<Parameters<typeof TransferDialog>[0]>) => {
  const onDone = vi.fn();
  const onClose = vi.fn();
  const view = render(<TransferDialog mode="copy" elements={[perso]} folders={folders} userId="u1" groupIds={[]} fromTree="owner" onClose={onClose} onDone={onDone} {...props} />);
  return { ...view, onDone, onClose };
};
const q = (sel: string) => document.querySelector(sel) as HTMLElement;
const flush = async () => act(async () => void (await Promise.resolve()));

describe("TransferDialog", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    Object.values(svc).forEach((f) => f.mockReset());
  });
  afterEach(() => vi.useRealTimers());

  it("propose Mes documents et les dossiers partagés où l'on peut contribuer", () => {
    show({});
    expect(q("[data-picker-root]")).toBeTruthy();
    expect(q('[data-picker-folder="fp"]')).toBeTruthy();
    expect(q('[data-picker-folder="fs"]')).toBeTruthy();
  });

  it("copie : progression, message de fin, puis fermeture", async () => {
    svc.copy.mockResolvedValue([]);
    const { onDone } = show({});
    fireEvent.click(q('[data-picker-folder="fs"]'));
    fireEvent.click(q("[data-picker-confirm]"));
    expect(q("[data-transfer-processing]")).toBeTruthy();
    await flush();
    expect(svc.copy).toHaveBeenCalledWith([perso], "fs");
    expect(q("[data-transfer-finished]")).toBeTruthy();
    expect(onDone).not.toHaveBeenCalled();
    act(() => void vi.advanceTimersByTime(1000));
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it("déplacement personnel → personnel : sans avertissement", async () => {
    svc.move.mockResolvedValue(undefined);
    show({ mode: "move" });
    fireEvent.click(q('[data-picker-folder="fp"]'));
    fireEvent.click(q("[data-picker-confirm]"));
    expect(q("[data-transfer-impact]")).toBeNull();
    await flush();
    expect(svc.move).toHaveBeenCalledWith([perso], "fp");
  });

  it("déplacement vers un dossier partagé : avertissement, avec le choix de copier à la place", async () => {
    svc.copy.mockResolvedValue([]);
    show({ mode: "move" });
    fireEvent.click(q('[data-picker-folder="fs"]'));
    fireEvent.click(q("[data-picker-confirm]"));
    expect(q("[data-transfer-impact]")?.textContent).toContain("workspace.move.window.toshare");
    expect(svc.move).not.toHaveBeenCalled();
    fireEvent.click(q("[data-transfer-copy]"));
    await flush();
    expect(svc.copy).toHaveBeenCalledWith([perso], "fs");
    expect(svc.move).not.toHaveBeenCalled();
  });

  it("déplacement confirmé vers un dossier partagé", async () => {
    svc.move.mockResolvedValue(undefined);
    show({ mode: "move" });
    fireEvent.click(q('[data-picker-folder="fs"]'));
    fireEvent.click(q("[data-picker-confirm]"));
    fireEvent.click(q("[data-transfer-move]"));
    await flush();
    expect(svc.move).toHaveBeenCalledWith([perso], "fs");
  });

  it("depuis « Documents partagés » vers Mes documents : copie puis suppression des originaux", async () => {
    svc.moveForShared.mockResolvedValue(undefined);
    show({ mode: "move", elements: [partage], fromTree: "shared" });
    fireEvent.click(q("[data-picker-root]"));
    fireEvent.click(q("[data-picker-confirm]"));
    expect(q("[data-transfer-impact]")?.textContent).toContain("workspace.move.window.fromshare.notmine");
    fireEvent.click(q("[data-transfer-move]"));
    await flush();
    expect(svc.moveForShared).toHaveBeenCalledWith([partage], undefined);
    expect(svc.move).not.toHaveBeenCalled();
  });

  it("explique l'échec avec la clé d'erreur du serveur", async () => {
    svc.copy.mockRejectedValue(new Error("workspace.contrib.cant"));
    show({});
    fireEvent.click(q('[data-picker-folder="fs"]'));
    fireEvent.click(q("[data-picker-confirm]"));
    await flush();
    expect(q("[data-transfer-error]")?.textContent).toBe("workspace.contrib.cant");
  });

  it("interdit de déposer un dossier dans lui-même", () => {
    show({ elements: [dossierPerso] });
    expect((q('[data-picker-folder="fp"]') as HTMLButtonElement).disabled).toBe(true);
  });
});
