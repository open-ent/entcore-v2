import { render, screen, act } from "@testing-library/react";
import { LoolCreateModal } from "./LoolCreateModal";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const send = (data: unknown, origin = window.location.origin) =>
  act(() => {
    window.dispatchEvent(new MessageEvent("message", { data: typeof data === "string" ? data : JSON.stringify(data), origin }));
  });

describe("LoolCreateModal", () => {
  it("ne s'affiche pas fermée", () => {
    const { container } = render(<LoolCreateModal isOpen={false} onClose={vi.fn()} onResync={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("charge la fenêtre du connecteur dans le dossier courant", () => {
    render(<LoolCreateModal isOpen folderId="f1" onClose={vi.fn()} onResync={vi.fn()} />);
    expect(document.querySelector("[data-lool-iframe]")?.getAttribute("src")).toBe("/lool/modal/create?folderId=f1");
  });

  it("répond à la demande de dossier, recharge, puis se ferme", () => {
    const onClose = vi.fn();
    const onResync = vi.fn();
    const spy = vi.spyOn(window, "postMessage");
    render(<LoolCreateModal isOpen folderId="f1" onClose={onClose} onResync={onResync} />);
    send({ id: "lool@getFolder" });
    expect(spy).toHaveBeenCalledWith(JSON.stringify({ id: "lool@getFolderResponse", folderId: "f1" }), window.location.origin);
    send({ id: "lool@resync" });
    expect(onResync).toHaveBeenCalledTimes(1);
    send({ id: "lool@close-modal" });
    expect(onClose).toHaveBeenCalled();
    spy.mockRestore();
  });

  it("ignore les messages d'une autre origine", () => {
    const onClose = vi.fn();
    render(<LoolCreateModal isOpen onClose={onClose} onResync={vi.fn()} />);
    send({ id: "lool@close-modal" }, "https://autre.example");
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});
