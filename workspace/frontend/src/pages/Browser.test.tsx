import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { Browser } from "./Browser";
import { WorkspaceElement } from "../lib/types";

vi.mock("@open-ent/react", () => ({
  AppHeader: ({ children }: { children: unknown }) => <div>{children as never}</div>,
  LoadingScreen: () => <div data-loading />,
  SegmentedControl: ({ value }: { value: string }) => <div data-segmented={value} />,
  useEdificeClient: () => ({ user: { userId: "u1" } }),
}));
vi.mock("../components/NewFolderModal", () => ({ NewFolderModal: () => null }));
vi.mock("../components/ZipDialog", () => ({ ZipDialog: () => null }));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const content = vi.hoisted(() => ({ value: {} as Record<string, unknown> }));
vi.mock("../services/queries", () => ({
  useContent: () => content.value,
  useFolders: () => ({ data: [{ _id: "f1", name: "Cours", eType: "folder", eParent: null, owner: "u1" }] }),
  useQuota: () => ({ data: { quota: 1000, storage: 250 } }),
  useViewModePreference: () => ({ data: "icons" }),
}));

const el = (p: Partial<WorkspaceElement>): WorkspaceElement => ({ _id: "x", name: "n", eType: "file", eParent: null, owner: "u1", ...p });

const renderAt = (url: string) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/workspace/:tree" element={<Browser />} />
          <Route path="/workspace/:tree/:folderId" element={<Browser />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

describe("Browser", () => {
  it("liste les dossiers avant les documents et affiche la jauge", () => {
    content.value = {
      isLoading: false,
      isError: false,
      isSuccess: true,
      data: [el({ _id: "d1", name: "zeta.txt" }), el({ _id: "f1", name: "Cours", eType: "folder" })],
    };
    renderAt("/workspace/owner");
    const ids = [...document.querySelectorAll("[data-element]")].map((n) => n.getAttribute("data-element-id"));
    expect(ids).toEqual(["f1", "d1"]);
    expect(screen.getByText(/250 o/)).toBeInTheDocument();
  });

  it("signale une erreur de chargement au lieu d'un dossier vide", () => {
    content.value = { isLoading: false, isError: true, isSuccess: false, data: undefined, refetch: vi.fn() };
    renderAt("/workspace/owner");
    expect(document.querySelector("[data-workspace-error]")).toBeInTheDocument();
    expect(document.querySelector("[data-workspace-empty]")).not.toBeInTheDocument();
  });

  it("propose créer et importer dans Mes documents, pas dans la corbeille", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [] };
    const { unmount } = renderAt("/workspace/owner");
    expect(document.querySelector('[data-action="new-folder"]')).toBeInTheDocument();
    expect(document.querySelector('[data-action="import"]')).toBeInTheDocument();
    unmount();
    renderAt("/workspace/trash");
    expect(document.querySelector("[data-workspace-toolbar]")).not.toBeInTheDocument();
  });

  it("affiche un message pour un dossier vide", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [] };
    renderAt("/workspace/trash");
    expect(document.querySelector("[data-workspace-empty]")).toBeInTheDocument();
  });

  it("construit le fil d'Ariane du dossier ouvert", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [] };
    renderAt("/workspace/owner/f1");
    expect(document.querySelector("[data-workspace-breadcrumb]")?.textContent).toContain("Cours");
  });
});
