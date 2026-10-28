import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { Viewer } from "./Viewer";
import { WorkspaceElement } from "../lib/types";

vi.mock("@open-ent/react", () => ({
  AppHeader: ({ children }: { children: unknown }) => <div>{children as never}</div>,
  LoadingScreen: () => <div data-loading />,
}));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const state = vi.hoisted(() => ({ element: {} as Record<string, unknown>, text: {} as Record<string, unknown> }));
vi.mock("../services/queries", () => ({ useElement: () => state.element, useTextContent: () => state.text }));

const file = (name: string, type: string): WorkspaceElement => ({ _id: "f1", name, eType: "file", eParent: null, owner: "u", ownerName: "Moi", metadata: { "content-type": type } });
const show = (el: WorkspaceElement, text: Record<string, unknown> = { isLoading: false, isError: false, data: "" }, ids?: string[], at = "f1") => {
  state.element = { isLoading: false, isError: false, data: el };
  state.text = text;
  return render(
    <MemoryRouter initialEntries={[{ pathname: `/view/${at}`, state: ids ? { ids } : undefined }]}>
      <Routes>
        <Route path="/view/:id" element={<Viewer />} />
      </Routes>
    </MemoryRouter>,
  );
};

describe("Viewer", () => {
  it("affiche une image", () => {
    show(file("a.png", "image/png"));
    expect(document.querySelector('[data-viewer="image"]')).toBeInTheDocument();
  });

  it("affiche un CSV en tableau", () => {
    show(file("a.csv", "text/csv"), { isLoading: false, isError: false, data: "nom;note\nAda;18" });
    const cells = [...document.querySelectorAll("[data-viewer-table] td")].map((c) => c.textContent);
    expect(cells).toEqual(["Ada", "18"]);
  });

  it("isole un contenu HTML dans un cadre sans aucun droit", () => {
    show(file("a.html", "text/html"), { isLoading: false, isError: false, data: "<script>alert(1)</script>" });
    const frame = document.querySelector('[data-viewer="html"]') as HTMLIFrameElement;
    expect(frame.getAttribute("sandbox")).toBe("");
  });

  it("signale un contenu illisible au lieu d'un affichage vide", () => {
    show(file("a.txt", "text/plain"), { isLoading: false, isError: true });
    expect(document.querySelector("[data-viewer-error]")).toBeInTheDocument();
  });

  it("propose seulement le téléchargement pour une archive", () => {
    show(file("a.zip", "application/zip"));
    expect(document.querySelector('[data-viewer="none"] a')?.getAttribute("href")).toBe("/workspace/document/f1");
  });

  it("précédent et suivant selon les documents du dossier", () => {
    show(file("a.png", "image/png"), undefined, ["f0", "f1", "f2"]);
    expect(screen.getByText("workspace.react.viewer.previous")).not.toBeDisabled();
    expect(screen.getByText("workspace.react.viewer.next")).not.toBeDisabled();
    fireEvent.click(screen.getByText("workspace.react.viewer.next"));
  });

  it("sans liste de documents, pas de précédent ni de suivant", () => {
    show(file("a.png", "image/png"));
    expect(document.querySelector("[data-viewer-prev]")).not.toBeInTheDocument();
  });
});
