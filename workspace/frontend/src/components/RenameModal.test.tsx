import { fireEvent, render } from "@testing-library/react";
import { RenameModal } from "./RenameModal";
import { WorkspaceElement } from "../lib/types";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock("@open-ent/react", () => {
  const Modal = ({ isOpen, children }: { isOpen: boolean; children: unknown }) => (isOpen ? <div>{children as never}</div> : null);
  Modal.Header = ({ children }: { children: unknown }) => <h2>{children as never}</h2>;
  Modal.Body = ({ children }: { children: unknown }) => <div>{children as never}</div>;
  Modal.Footer = ({ children }: { children: unknown }) => <div>{children as never}</div>;
  const Button = ({ children, onClick, type, ...rest }: Record<string, unknown>) => (
    <button type={(type as "button" | "submit") ?? "button"} onClick={onClick as never} {...(rest as object)}>
      {children as never}
    </button>
  );
  return { Modal, Button };
});
const parent = vi.hoisted(() => ({ value: { data: undefined } as Record<string, unknown> }));
vi.mock("../services/queries", () => ({ useParentInfo: () => parent.value }));

const el = (p: Partial<WorkspaceElement>): WorkspaceElement => ({ _id: "x", name: "rapport.txt", eType: "file", eParent: null, owner: "u1", ...p });
const input = () => document.querySelector("#workspace-rename-name") as HTMLInputElement;
const PREFIX = "org-entcore-workspace-controllers-WorkspaceController|";

describe("RenameModal — propriétés", () => {
  beforeEach(() => (parent.value = { data: undefined }));

  it("titre « Propriétés » et libellé du champ selon le type d'élément", () => {
    const { container, rerender } = render(<RenameModal element={el({})} onClose={vi.fn()} onRename={vi.fn()} />);
    expect(container.querySelector("h2")?.textContent).toBe("workspace.properties");
    expect(container.querySelector("label")?.textContent).toBe("workspace.document.name");
    rerender(<RenameModal element={el({ eType: "folder" })} onClose={vi.fn()} onRename={vi.fn()} />);
    expect(container.querySelector("label")?.textContent).toBe("workspace.folder.name");
  });

  it("limite à 80 caractères et refuse les caractères interdits à la frappe", () => {
    render(<RenameModal element={el({})} onClose={vi.fn()} onRename={vi.fn()} />);
    expect(input().maxLength).toBe(80);
    expect(fireEvent.keyDown(input(), { key: "/" })).toBe(false); // preventDefault appelé
    expect(fireEvent.keyDown(input(), { key: "a" })).toBe(true);
  });

  it("enregistre un nom nettoyé, extension conservée", () => {
    const onRename = vi.fn();
    render(<RenameModal element={el({})} onClose={vi.fn()} onRename={onRename} />);
    fireEvent.change(input(), { target: { value: "  nouveau<>nom|  " } });
    fireEvent.submit(document.querySelector("[data-rename-form]")!);
    expect(onRename).toHaveBeenCalledWith("nouveaunom.txt");
  });

  it("refuse un nom vide une fois nettoyé", () => {
    const onRename = vi.fn();
    render(<RenameModal element={el({})} onClose={vi.fn()} onRename={onRename} />);
    fireEvent.change(input(), { target: { value: " <> " } });
    fireEvent.submit(document.querySelector("[data-rename-form]")!);
    expect(onRename).not.toHaveBeenCalled();
    expect(document.querySelector("[data-rename-error]")).not.toBeNull();
  });

  it("affiche le dossier parent et ses gestionnaires", () => {
    parent.value = {
      data: {
        _id: "p1",
        name: "Cours",
        eType: "folder",
        eParent: null,
        owner: "u1",
        ownerName: "Alice",
        inheritedShares: [{ userId: "u2", [PREFIX + "renameFolder"]: true }],
        visibleUsers: [{ id: "u2", username: "Bob" }],
      },
    };
    render(<RenameModal element={el({ eParent: "p1" })} onClose={vi.fn()} onRename={vi.fn()} parentHref={(id) => `#/owner/${id}`} />);
    expect(document.querySelector("[data-properties-parent] a")?.getAttribute("href")).toBe("#/owner/p1");
    expect([...document.querySelectorAll("[data-properties-managers] li")].map((l) => l.textContent)).toEqual(["Alice", "Bob"]);
  });

  it("sans dossier parent : pas de bloc parent", () => {
    render(<RenameModal element={el({})} onClose={vi.fn()} onRename={vi.fn()} />);
    expect(document.querySelector("[data-properties-parent]")).toBeNull();
  });
});
