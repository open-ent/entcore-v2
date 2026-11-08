import { fireEvent, render } from "@testing-library/react";
import { WorkspaceElement } from "../lib/types";
import { ViewerModal } from "./ViewerModal";

vi.mock("@open-ent/react", async () => (await import("../test/socle")).socleMock({
  useEdificeClient: () => ({ user: { userId: "u1", authorizedActions: [] } }),
}));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock("../services/workspace.service", () => ({ updateImage: vi.fn() }));

const state = vi.hoisted(() => ({ element: {} as Record<string, unknown>, text: {} as Record<string, unknown> }));
vi.mock("../services/queries", () => ({
  useElement: () => state.element,
  useTextContent: () => state.text,
  useLoolContext: () => ({ data: undefined }),
}));
vi.mock("@tanstack/react-query", async () => {
  const actual = await vi.importActual<Record<string, unknown>>("@tanstack/react-query");
  return { ...actual, useQuery: () => ({ data: true, isLoading: false }), useQueryClient: () => ({ invalidateQueries: vi.fn() }) };
});

const file = (name: string, type: string): WorkspaceElement =>
  ({ _id: "f1", name, eType: "file", eParent: null, owner: "u1", ownerName: "Moi", metadata: { "content-type": type } }) as WorkspaceElement;

const show = (el: WorkspaceElement, siblings: string[] = []) => {
  state.element = { data: el, isLoading: false, isError: false };
  state.text = { data: "a;b\n1;2", isLoading: false, isError: false };
  return render(<ViewerModal id={el._id} siblings={siblings} onClose={() => undefined} />);
};

describe("ViewerModal", () => {
  it("ne s'ouvre pas sans document", () => {
    state.element = { data: undefined, isLoading: false, isError: false };
    const { container } = render(<ViewerModal onClose={() => undefined} />);
    expect(container.innerHTML).toBe("");
  });

  it("affiche chaque type de contenu à sa façon", () => {
    for (const [name, type, viewer] of [
      ["photo.jpg", "image/jpeg", "image"],
      ["cours.pdf", "application/pdf", "pdf"],
      ["clip.mp4", "video/mp4", "video"],
      ["son.mp3", "audio/mpeg", "audio"],
      ["notes.txt", "text/plain", "text"],
      ["page.html", "text/html", "html"],
      ["archive.zip", "application/zip", "none"],
    ] as const) {
      const { container, unmount } = show(file(name, type));
      expect(container.querySelector(`[data-viewer="${viewer}"]`), name).not.toBeNull();
      unmount();
    }
  });

  it("affiche un CSV en tableau et nomme le document", () => {
    const { container } = show(file("notes.csv", "text/csv"));
    expect(container.querySelector("[data-viewer-table]")).not.toBeNull();
    expect(container.querySelector("[data-viewer-title]")?.textContent).toBe("notes.csv");
  });

  it("propose le téléchargement, et le plein écran", () => {
    const { container } = show(file("photo.jpg", "image/jpeg"));
    expect(container.querySelector("[data-viewer-download]")?.getAttribute("href")).toBe("/workspace/document/f1");
    expect(container.querySelector("[data-viewer-fullscreen]")).not.toBeNull();
  });

  it("sans documents voisins, ni précédent ni suivant", () => {
    const { container } = show(file("photo.jpg", "image/jpeg"));
    expect(container.querySelector("[data-viewer-prev]")).toBeNull();
    expect(container.querySelector("[data-viewer-next]")).toBeNull();
  });

  it("précédent et suivant sont bornés aux extrémités du dossier", () => {
    const { container } = show(file("photo.jpg", "image/jpeg"), ["f0", "f1", "f2"]);
    expect(container.querySelector("[data-viewer-prev]")).not.toBeNull();
    expect(container.querySelector("[data-viewer-next]")).not.toBeNull();
    const { container: first, unmount } = render(
      <ViewerModal id="f1" siblings={["f1", "f2"]} onClose={() => undefined} />,
    );
    expect(first.querySelector("[data-viewer-prev]")).toHaveAttribute("disabled");
    unmount();
  });

  it("une flèche du clavier passe au document suivant", () => {
    const changed: string[] = [];
    state.element = { data: file("photo.jpg", "image/jpeg"), isLoading: false, isError: false };
    state.text = { data: "", isLoading: false, isError: false };
    render(<ViewerModal id="f1" siblings={["f1", "f2"]} onClose={() => undefined} onCurrentChange={(n) => changed.push(n)} />);
    fireEvent.keyDown(window, { key: "ArrowRight" });
    expect(changed).toEqual(["f2"]);
  });

  it("signale un document illisible au lieu d'un affichage vide", () => {
    state.element = { data: undefined, isLoading: false, isError: true };
    state.text = { data: "", isLoading: false, isError: false };
    const { container } = render(<ViewerModal id="f1" onClose={() => undefined} />);
    expect(container.querySelector("[data-viewer-error]")).not.toBeNull();
  });
});
