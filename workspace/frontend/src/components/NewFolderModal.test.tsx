import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { NewFolderModal } from "./NewFolderModal";

// Fonction ordinaire (pas un espion) : vitest rapporte comme erreur du test un rejet renvoyé par un vi.fn().
const state = vi.hoisted(() => ({ impl: (() => Promise.resolve({ _id: "n1" })) as (...a: unknown[]) => Promise<unknown>, calls: [] as unknown[][] }));
vi.mock("../services/workspace.service", () => ({
  createFolder: (...a: unknown[]) => {
    state.calls.push(a);
    return state.impl(...a);
  },
}));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock("@open-ent/react", () => {
  const Modal = ({ children, isOpen }: { children: unknown; isOpen: boolean }) => (isOpen ? <div>{children as never}</div> : null);
  Modal.Header = ({ children }: { children: unknown }) => <h2>{children as never}</h2>;
  Modal.Body = ({ children }: { children: unknown }) => <div>{children as never}</div>;
  Modal.Footer = ({ children }: { children: unknown }) => <div>{children as never}</div>;
  return { Modal, Button: ({ children, ...p }: { children: unknown }) => <button {...(p as object)}>{children as never}</button> };
});

const renderModal = (onClose = vi.fn()) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <NewFolderModal parentId="p1" isOpen onClose={onClose} />
    </QueryClientProvider>,
  );

const type = (value: string) => fireEvent.change(screen.getByLabelText("workspace.folder.name"), { target: { value } });

describe("NewFolderModal", () => {
  beforeEach(() => {
    state.calls = [];
    state.impl = () => Promise.resolve({ _id: "n1" });
  });

  it("crée le dossier sous le parent et se ferme", async () => {
        const onClose = vi.fn();
    renderModal(onClose);
    type("  Cours  ");
    fireEvent.submit(document.querySelector("[data-new-folder-form]")!);
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(state.calls).toEqual([["Cours", "p1"]]);
  });

  it("refuse un nom avec _ sans appeler le serveur", () => {
    renderModal();
    type("a_b");
    fireEvent.submit(document.querySelector("[data-new-folder-form]")!);
    expect(document.querySelector("[data-new-folder-error]")?.textContent).toBe("workspace.folder.invalid.name");
    expect(state.calls).toHaveLength(0);
  });

  it("garde la fenêtre ouverte et traduit l'erreur du serveur (doublon)", async () => {
    state.impl = () => Promise.reject("workspace.folder.already.exists");
    const onClose = vi.fn();
    renderModal(onClose);
    type("Cours");
    await act(async () => {
      fireEvent.submit(document.querySelector("[data-new-folder-form]")!);
    });
    expect(document.querySelector("[data-new-folder-error]")?.textContent).toBe("workspace.folder.already.exists");
    expect(onClose).not.toHaveBeenCalled();
  });
});
