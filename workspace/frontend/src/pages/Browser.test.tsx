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
vi.mock("../components/TransferDialog", () => ({ TransferDialog: ({ mode }: { mode: string }) => <div data-folder-picker={mode === "copy" ? "workspace.react.picker.copy" : "workspace.react.picker.move"} /> }));
vi.mock("../components/RenameModal", () => ({ RenameModal: () => null }));
vi.mock("../components/ConfirmModal", () => ({ ConfirmModal: () => null }));
const prefs = vi.hoisted(() => ({ value: { view: "icons" } as Record<string, unknown>, save: vi.fn() }));
const elementActionsMock = vi.hoisted(() => ({}) as Record<string, unknown>);
vi.mock("../services/workspace.service", () => ({ elementActions: elementActionsMock, workspaceService: { savePreference: (p: unknown) => prefs.save(p) } }));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const content = vi.hoisted(() => ({ value: {} as Record<string, unknown>, search: { isError: false, isFetching: false, data: undefined } as Record<string, unknown> }));
vi.mock("../services/queries", () => ({
  useContent: () => content.value,
  useSearch: () => content.search,
  useLoolContext: () => ({ data: undefined }),
  usePublishedIds: () => ({ data: [] }),
  useNextcloudList: () => ({ data: [] }),
  useNextcloudQuota: () => ({ data: undefined }),
  useFolderStore: () => ({ lazy: false, isLoaded: () => false, loadChildren: async () => undefined, ensureFolder: async () => undefined }),
  useFolders: () => ({ data: [{ _id: "f1", name: "Cours", eType: "folder", eParent: null, owner: "u1" }] }),
  useQuota: () => ({ data: { quota: 1000, storage: 250 } }),
  useWorkspacePreference: () => ({ data: prefs.value }),
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
    // boutons directs ; « Organiser » et « Plus » regroupent le reste ; la corbeille est à droite
    expect(actions).toEqual(["download", "rename", "share", "trash"]);
    const menus = Object.fromEntries([...document.querySelectorAll("[data-menu-toggle]")].map((b) => [b.getAttribute("data-menu-toggle"), b.getAttribute("data-menu-actions")]));
    expect(menus).toEqual({ organise: "copy move", more: "history comments" });
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

  it("en liste, un clic sur un en-tête trie et mémorise ; un second clic inverse le sens", () => {
    prefs.value = { view: "list" };
    prefs.save.mockClear();
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [el({ _id: "a", name: "a.txt", metadata: { size: 5 } as never }), el({ _id: "b", name: "b.txt", metadata: { size: 50 } as never })] };
    renderAt("/owner");
    const ids = () => [...document.querySelectorAll("[data-element]")].map((n) => n.getAttribute("data-element-id"));
    fireEvent.click(document.querySelector('[data-sort-column="size"]')!);
    expect(ids()).toEqual(["a", "b"]);
    expect(prefs.save).toHaveBeenLastCalledWith({ sortField: "metadata.size", sortDesc: false });
    fireEvent.click(document.querySelector('[data-sort-column="size"]')!);
    expect(ids()).toEqual(["b", "a"]);
    expect(prefs.save).toHaveBeenLastCalledWith({ sortField: "metadata.size", sortDesc: true });
    prefs.value = { view: "icons" };
  });

  it("applique le tri mémorisé dans la préférence de l'usager", () => {
    prefs.value = { view: "list", sortField: "name", sortDesc: true };
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [el({ _id: "a", name: "a.txt" }), el({ _id: "b", name: "b.txt" })] };
    renderAt("/owner");
    expect([...document.querySelectorAll("[data-element]")].map((n) => n.getAttribute("data-element-id"))).toEqual(["b", "a"]);
    prefs.value = { view: "icons" };
  });

  const trio = () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: ["a", "b", "c", "d"].map((n) => el({ _id: n, name: `${n}.txt` })) };
  };
  const selectedIds = () => [...document.querySelectorAll('[data-element][data-selected="true"]')].map((n) => n.getAttribute("data-element-id"));

  it("Ctrl+A sélectionne tout, puis désélectionne", () => {
    trio();
    renderAt("/owner");
    fireEvent.keyDown(document, { key: "a", ctrlKey: true });
    expect(selectedIds()).toEqual(["a", "b", "c", "d"]);
    fireEvent.keyDown(document, { key: "a", ctrlKey: true });
    expect(selectedIds()).toEqual([]);
  });

  it("Ctrl+A dans un champ de saisie ne sélectionne pas les éléments", () => {
    trio();
    renderAt("/owner");
    fireEvent.keyDown(screen.getByRole("searchbox"), { key: "a", ctrlKey: true });
    expect(selectedIds()).toEqual([]);
  });

  it("Maj + clic sélectionne la plage depuis le dernier élément coché", () => {
    trio();
    renderAt("/owner");
    const boxes = () => [...document.querySelectorAll("[data-element-select]")] as HTMLElement[];
    fireEvent.click(boxes()[0]);
    fireEvent.click(boxes()[2], { shiftKey: true });
    expect(selectedIds()).toEqual(["a", "b", "c"]);
  });

  it("Ctrl+C et Ctrl+X ouvrent la copie et le déplacement de la sélection", () => {
    trio();
    renderAt("/owner");
    fireEvent.keyDown(document, { key: "c", ctrlKey: true });
    expect(document.querySelector("[data-folder-picker]")).toBeNull(); // rien de sélectionné : rien ne s'ouvre
    fireEvent.click(document.querySelector("[data-element-select]")!);
    fireEvent.keyDown(document, { key: "c", ctrlKey: true });
    expect(document.querySelector("[data-folder-picker]")?.getAttribute("data-folder-picker")).toBe("workspace.react.picker.copy");
  });

  it("Ctrl+X ouvre le déplacement", () => {
    trio();
    renderAt("/owner");
    fireEvent.click(document.querySelector("[data-element-select]")!);
    fireEvent.keyDown(document, { key: "x", ctrlKey: true });
    expect(document.querySelector("[data-folder-picker]")?.getAttribute("data-folder-picker")).toBe("workspace.react.picker.move");
  });

  it("affiche les éléments par tranches de 50 avec « Afficher la suite »", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: Array.from({ length: 120 }, (_, i) => el({ _id: `e${String(i).padStart(3, "0")}`, name: `f${String(i).padStart(3, "0")}.txt` })) };
    renderAt("/owner");
    const count = () => document.querySelectorAll("[data-element]").length;
    expect(count()).toBe(50);
    fireEvent.click(document.querySelector("[data-load-more] button")!);
    expect(count()).toBe(100);
    fireEvent.click(document.querySelector("[data-load-more] button")!);
    expect(count()).toBe(120);
    expect(document.querySelector("[data-load-more]")).toBeNull();
  });

  it("Ctrl+A ne sélectionne que les éléments affichés", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: Array.from({ length: 120 }, (_, i) => el({ _id: `e${String(i).padStart(3, "0")}`, name: `f${String(i).padStart(3, "0")}.txt` })) };
    renderAt("/owner");
    fireEvent.keyDown(document, { key: "a", ctrlKey: true });
    expect(document.querySelectorAll('[data-element][data-selected="true"]').length).toBe(50);
  });

  it("un emplacement vide affiche le texte propre à son arbre", () => {
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [] };
    const { unmount } = renderAt("/shared");
    expect(document.querySelector("[data-workspace-empty]")?.textContent).toContain("empty.workspace.shared.title");
    expect(document.querySelector("[data-workspace-empty]")?.textContent).toContain("empty.workspace.shared");
    unmount();
    renderAt("/trash");
    expect(document.querySelector("[data-workspace-empty]")?.textContent).toContain("empty.trash2");
  });

  it("met à la corbeille avec un message de succès", async () => {
    const trash = vi.fn().mockResolvedValue(undefined);
    elementActionsMock.trash = trash;
    content.value = { isLoading: false, isError: false, isSuccess: true, data: [el({ _id: "d1", name: "a.txt" })] };
    renderAt("/owner");
    fireEvent.click(document.querySelector("[data-element-select]")!);
    fireEvent.click(document.querySelector('[data-action-bar] [data-action="trash"]')!);
    await vi.waitFor(() => expect(document.querySelector("[data-workspace-success]")?.textContent).toBe("workspace.removed.message"));
    expect(trash).toHaveBeenCalled();
  });
});
