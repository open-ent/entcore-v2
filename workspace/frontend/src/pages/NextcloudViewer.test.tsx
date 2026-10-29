import { fireEvent, render } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { NextcloudViewer } from "./NextcloudViewer";
import { SyncDocument } from "../lib/nextcloud";

vi.mock("@open-ent/react", () => ({
  AppHeader: ({ children }: { children: unknown }) => <div>{children as never}</div>,
  LoadingScreen: () => <div data-loading />,
  useEdificeClient: () => ({ user: { userId: "u1" } }),
}));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock("./Viewer", () => ({ Content: ({ element, source }: { element: { name: string }; source?: string }) => <div data-content={element.name} data-source={source} /> }));
const list = vi.hoisted(() => ({ value: {} as Record<string, unknown> }));
vi.mock("../services/queries", () => ({ useNextcloudList: () => list.value }));

const d = (p: Partial<SyncDocument>): SyncDocument => ({ path: "Cours/a.pdf", name: "a.pdf", isFolder: false, kind: "pdf", editable: false, contentType: "application/pdf", ...p });
const Where = () => <span data-where>{useLocation().pathname + useLocation().search}</span>;
const show = (url: string) =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/nextcloud-view" element={<NextcloudViewer />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );

describe("NextcloudViewer", () => {
  beforeEach(() => {
    list.value = { isLoading: false, data: [d({ path: "Cours/dossier", name: "dossier", isFolder: true }), d({}), d({ path: "Cours/b.txt", name: "b.txt", contentType: "text/plain" })] };
  });

  it("affiche le fichier à partir de son adresse de téléchargement en ligne", () => {
    show("/nextcloud-view?path=Cours%2Fa.pdf");
    expect(document.querySelector("[data-viewer-title]")?.textContent).toBe("a.pdf");
    expect(document.querySelector("[data-content]")?.getAttribute("data-source")).toBe("/nextcloud/files/user/u1/file/a.pdf/download?path=Cours/a.pdf&contentType=application/pdf&isFolder=false&inline=true");
    expect((document.querySelector("[data-viewer-download]") as HTMLAnchorElement).getAttribute("href")).toContain("/nextcloud/files/user/u1/file/a.pdf/download");
  });

  it("précédent et suivant parcourent les fichiers du dossier, sans les dossiers", () => {
    show("/nextcloud-view?path=Cours%2Fa.pdf");
    expect((document.querySelector("[data-viewer-prev]") as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(document.querySelector("[data-viewer-next]")!);
    expect(document.querySelector("[data-viewer-title]")?.textContent).toBe("b.txt");
  });

  it("revient au dossier du fichier", () => {
    show("/nextcloud-view?path=Cours%2Fa.pdf");
    fireEvent.click(document.querySelector("[data-viewer-back]")!);
    expect(document.querySelector("[data-where]")?.textContent).toBe("/nextcloud?path=Cours");
  });

  it("signale un fichier introuvable", () => {
    show("/nextcloud-view?path=Cours%2Fabsent.pdf");
    expect(document.querySelector("[data-viewer-error]")).not.toBeNull();
  });
});
