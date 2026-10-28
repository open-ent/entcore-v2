import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { Browser } from "./Browser";
import { WorkspaceElement } from "../lib/types";

vi.mock("@open-ent/react", () => ({
  AppHeader: ({ children }: { children: unknown }) => <div>{children as never}</div>,
  LoadingScreen: () => <div data-loading />,
  SegmentedControl: ({ value }: { value: string }) => <div data-segmented={value} />,
  ShareModal: () => null,
  useEdificeClient: () => ({ user: { userId: "u1" } }),
}));
vi.mock("../components/NewFolderModal", () => ({ NewFolderModal: () => null }));
vi.mock("../components/ZipDialog", () => ({ ZipDialog: () => null }));
vi.mock("../components/VersionsModal", () => ({ VersionsModal: () => null }));
vi.mock("../components/CommentsModal", () => ({ CommentsModal: () => null }));
vi.mock("../components/LoolCreateModal", () => ({ LoolCreateModal: () => null }));
vi.mock("../components/NextcloudPicker", () => ({ NextcloudPicker: () => null }));
vi.mock("../components/PublishModal", () => ({ PublishModal: () => null }));
vi.mock("../components/ShareWarning", () => ({ ShareWarning: () => null }));
vi.mock("../components/FolderPicker", () => ({ FolderPicker: () => null }));
vi.mock("../components/RenameModal", () => ({ RenameModal: () => null }));
vi.mock("../components/ConfirmModal", () => ({ ConfirmModal: () => null }));
vi.mock("../services/workspace.service", () => ({ elementActions: {} }));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const content = vi.hoisted(() => ({ value: {} as Record<string, unknown>, search: { isError: false, isFetching: false, data: undefined } as Record<string, unknown> }));
vi.mock("../services/queries", () => ({
  useContent: () => content.value,
  useSearch: () => content.search,
  useLoolContext: () => ({ data: undefined }),
  usePublishedIds: () => ({ data: [] }),
  useNextcloudList: () => ({ data: [] }),
  useNextcloudQuota: () => ({ data: undefined }),
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
          <Route path="/:tree" element={<Browser />} />
          <Route path="/:tree/:folderId" element={<Browser />} />
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
    renderAt("/owner");
    const ids = [...document.querySelectorAll("[data-element]")].map((n) => n.getAttribute("data-element-id"));
    expect(ids).toEqual(["f1", "d1"]);
    expect(screen.getByText(/250 o/)).toBeInTheDocument();
  });

  it("signale une erreur de chargement au lieu d'un dossier vide", () => {
    content.value = { isLoading: false, isError: true, isSuccess: false, data: undefined, refetch: vi.fn() };
    renderAt("/owner");
    expect(document.querySelector("[data-workspace-error]")).toBeInTheDocument();
    expect(document.querySelector("[data-workspace-empty]")).not.toBeInTheDocument();
  });

  it("propose créer et importer dans Mes documents, pas dans la corbeille", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [] };
    const { unmount } = renderAt("/owner");
    expect(document.querySelector('[data-action="new-folder"]')).toBeInTheDocument();
    expect(document.querySelector('[data-action="import"]')).toBeInTheDocument();
    unmount();
    renderAt("/trash");
    expect(document.querySelector("[data-workspace-toolbar]")).not.toBeInTheDocument();
  });

  it("sélectionner un élément de Mes documents propose les actions de gestion", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [el({ _id: "d1", name: "a.txt" })] };
    renderAt("/owner");
    expect(document.querySelector("[data-action-bar]")).not.toBeInTheDocument();
    fireEvent.click(document.querySelector("[data-element-select]")!);
    const actions = [...document.querySelectorAll("[data-action-bar] [data-action]")].map((b) => b.getAttribute("data-action"));
    expect(actions).toEqual(["rename", "share", "copy", "move", "trash", "download", "history", "comments"]);
  });

  it("dans la corbeille, la sélection propose restaurer et supprimer, et le vidage est proposé", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [el({ _id: "d1", name: "a.txt" })] };
    renderAt("/trash");
    expect(document.querySelector('[data-action="empty-trash"]')).toBeInTheDocument();
    fireEvent.click(document.querySelector("[data-element-select]")!);
    const actions = [...document.querySelectorAll("[data-action-bar] [data-action]")].map((b) => b.getAttribute("data-action"));
    expect(actions).toEqual(["restore", "delete"]);
  });

  it("une recherche affiche les résultats du serveur et propose de chercher partout", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [el({ _id: "d1", name: "autre.txt" })] };
    content.search = { isError: false, isFetching: false, data: [el({ _id: "r1", name: "devoir.txt" })] };
    renderAt("/owner");
    fireEvent.change(document.querySelector("[data-search-input]")!, { target: { value: "devoir" } });
    fireEvent.submit(document.querySelector("[data-workspace-search]")!);
    const ids = [...document.querySelectorAll("[data-element]")].map((n) => n.getAttribute("data-element-id"));
    expect(ids).toEqual(["r1"]);
    expect(document.querySelector("[data-search-everywhere]")).toBeInTheDocument();
    fireEvent.click(document.querySelector("[data-search-reset]")!);
    expect(document.querySelector("[data-search-banner]")).not.toBeInTheDocument();
    content.search = { isError: false, isFetching: false, data: undefined };
  });

  it("si la recherche serveur échoue, filtre les noms affichés et le dit", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [el({ _id: "a", name: "devoir.txt" }), el({ _id: "b", name: "zebre.txt" })] };
    content.search = { isError: true, isFetching: false, data: undefined };
    renderAt("/owner");
    fireEvent.change(document.querySelector("[data-search-input]")!, { target: { value: "dev" } });
    fireEvent.submit(document.querySelector("[data-workspace-search]")!);
    expect([...document.querySelectorAll("[data-element]")].map((n) => n.getAttribute("data-element-id"))).toEqual(["a"]);
    expect(document.querySelector("[data-search-degraded]")).toBeInTheDocument();
    content.search = { isError: false, isFetching: false, data: undefined };
  });

  it("affiche un message pour un dossier vide", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [] };
    renderAt("/trash");
    expect(document.querySelector("[data-workspace-empty]")).toBeInTheDocument();
  });

  it("construit le fil d'Ariane du dossier ouvert", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [] };
    renderAt("/owner/f1");
    expect(document.querySelector("[data-workspace-breadcrumb]")?.textContent).toContain("Cours");
  });
});
