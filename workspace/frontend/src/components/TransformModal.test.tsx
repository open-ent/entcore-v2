import { fireEvent, render, waitFor } from "@testing-library/react";
import { TransformModal } from "./TransformModal";
import { WorkspaceElement } from "../lib/types";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock("@open-ent/react", () => {
  const Modal = ({ isOpen, children }: { isOpen: boolean; children: unknown }) => (isOpen ? <div>{children as never}</div> : null);
  Modal.Header = ({ children }: { children: unknown }) => <h2>{children as never}</h2>;
  Modal.Body = ({ children }: { children: unknown }) => <div>{children as never}</div>;
  Modal.Footer = ({ children }: { children: unknown }) => <div>{children as never}</div>;
  const Button = ({ children, onClick, type, isLoading: _l, ...rest }: Record<string, unknown>) => (
    <button type={(type as "button" | "submit") ?? "button"} onClick={onClick as never} {...(rest as object)}>
      {children as never}
    </button>
  );
  return { Modal, Button };
});
const call = vi.hoisted(() => ({ fn: vi.fn(), fail: null as string | null }));
// un refus passe par une fonction ordinaire : la promesse rejetée gardée par l'espion serait signalée comme non gérée
vi.mock("../services/transform.service", () => ({
  transformDocuments: (r: unknown) => (call.fail ? Promise.reject(call.fail) : call.fn(r)),
}));

const pdf = (id: string, name = `${id}.pdf`): WorkspaceElement => ({ _id: id, name, eType: "file", eParent: null, owner: "u", metadata: { filename: name } as WorkspaceElement["metadata"] });
const q = (sel: string) => document.querySelector(sel) as HTMLInputElement;
const submit = () => fireEvent.submit(q("[data-transform-form]"));

describe("TransformModal", () => {
  beforeEach(() => {
    call.fail = null;
    call.fn.mockReset().mockResolvedValue({ _id: "new" });
  });

  it("protéger : les deux saisies doivent concorder, puis le mot de passe part avec les restrictions", async () => {
    const onDone = vi.fn();
    render(<TransformModal transform="protectPdf" selection={[pdf("a")]} parentId="f1" canVersion onDone={onDone} onClose={vi.fn()} />);
    fireEvent.change(q("[data-transform-password]"), { target: { value: "Secret-42" } });
    fireEvent.change(q("[data-transform-confirm]"), { target: { value: "autre" } });
    submit();
    expect(q("[data-transform-error]").textContent).toBe("workspace.transform.password.mismatch");
    expect(call.fn).not.toHaveBeenCalled();
    fireEvent.change(q("[data-transform-confirm]"), { target: { value: "Secret-42" } });
    submit();
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(call.fn).toHaveBeenCalledWith(
      expect.objectContaining({
        operation: "encrypt",
        ids: ["a"],
        target: "new",
        parentId: "f1",
        options: { userPassword: "Secret-42", allowCopy: false, allowPrint: true, allowModify: false },
      }),
    );
  });

  it("fusionner : l'ordre se change avant l'envoi", async () => {
    render(<TransformModal transform="mergePdf" selection={[pdf("a"), pdf("b")]} canVersion={false} onDone={vi.fn()} onClose={vi.fn()} />);
    fireEvent.click(document.querySelectorAll("[data-transform-order] button")[1]); // « descendre » le premier
    submit();
    await waitFor(() => expect(call.fn).toHaveBeenCalled());
    expect(call.fn.mock.calls[0][0]).toMatchObject({ operation: "merge", ids: ["b", "a"], name: "workspace.transform.name.merge2" });
    expect(q("[data-transform-target]")).toBeNull();
  });

  it("pages : saisie invalide refusée, nouvelle version possible", async () => {
    render(<TransformModal transform="pdfPages" selection={[pdf("a")]} canVersion onDone={vi.fn()} onClose={vi.fn()} />);
    fireEvent.change(q("[data-transform-pages]"), { target: { value: "1;2" } });
    submit();
    expect(q("[data-transform-error]").textContent).toBe("workspace.transform.pages.invalid");
    fireEvent.change(q("[data-transform-pages]"), { target: { value: "3-fin" } });
    fireEvent.change(q("[data-transform-rotate]"), { target: { value: "90" } });
    fireEvent.click(q("[data-transform-version]"));
    submit();
    await waitFor(() => expect(call.fn).toHaveBeenCalled());
    expect(call.fn.mock.calls[0][0]).toMatchObject({ operation: "pages", target: "version", options: { select: "3-z", rotate: ["90"] }, name: undefined });
  });

  it("l'erreur du serveur s'affiche traduite dans la fenêtre", async () => {
    call.fail = "workspace.transform.password.invalid";
    render(<TransformModal transform="unlockPdf" selection={[pdf("a")]} canVersion onDone={vi.fn()} onClose={vi.fn()} />);
    fireEvent.change(q("[data-transform-password]"), { target: { value: "faux" } });
    submit();
    await waitFor(() => expect(q("[data-transform-error]")?.textContent).toBe("workspace.transform.password.invalid"));
  });
});
