import { fireEvent, render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { NextcloudBrowser } from "./NextcloudBrowser";
import { SyncDocument } from "../lib/nextcloud";

vi.mock("@open-ent/react", () => ({
  AppHeader: ({ children }: { children: unknown }) => <div>{children as never}</div>,
  LoadingScreen: () => <div data-loading />,
  useEdificeClient: () => ({ user: { userId: "u1" } }),
}));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock("../components/FolderPicker", () => ({ FolderPicker: () => null }));
vi.mock("../services/nextcloud.service", () => ({ nextcloudService: {} }));

const state = vi.hoisted(() => ({ list: {} as Record<string, unknown> }));
vi.mock("../services/queries", () => ({
  useNextcloudList: () => state.list,
  useNextcloudUrl: () => ({ data: { url: "https://nc.example", hidden: false } }),
  useFolders: () => ({ data: [] }),
}));

const d = (p: Partial<SyncDocument>): SyncDocument => ({ path: "a.txt", name: "a.txt", isFolder: false, kind: "text", editable: false, ...p });
const show = (list: Record<string, unknown>, trash = false, url = "/nextcloud") => {
  state.list = list;
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/nextcloud" element={<NextcloudBrowser trash={trash} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
};
const actions = () => [...document.querySelectorAll("[data-nc-action-bar] [data-nc-action]")].map((b) => b.getAttribute("data-nc-action"));

describe("NextcloudBrowser", () => {
  it("liste dossiers et fichiers, affiche l'adresse du serveur et propose création et envoi", () => {
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({ path: "Cours", name: "Cours", isFolder: true, kind: "folder" }), d({})] });
    expect([...document.querySelectorAll("[data-nc-item]")].map((n) => n.getAttribute("data-nc-item"))).toEqual(["folder", "file"]);
    expect(document.querySelector("[data-nc-url]")?.textContent).toContain("https://nc.example");
    expect(document.querySelector('[data-nc-action="new-folder"]')).toBeInTheDocument();
  });

  it("la sélection d'un fichier propose les actions NextCloud", () => {
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({})] });
    fireEvent.click(document.querySelector("[data-nc-select]")!);
    expect(actions()).toEqual(["download", "open", "rename", "copyToWorkspace", "moveToWorkspace", "trash"]);
  });

  it("dans la corbeille NextCloud : restaurer, supprimer, vider ; pas d'envoi", () => {
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({})] }, true);
    expect(document.querySelector('[data-nc-action="empty-trash"]')).toBeInTheDocument();
    expect(document.querySelector("[data-nc-toolbar]")).not.toBeInTheDocument();
    fireEvent.click(document.querySelector("[data-nc-select]")!);
    expect(actions()).toEqual(["restore", "deleteForever"]);
  });

  it("une erreur de chargement est signalée, pas un dossier vide", () => {
    show({ isLoading: false, isError: true, isSuccess: false, data: undefined, refetch: vi.fn() });
    expect(document.querySelector("[data-nc-error]")).toBeInTheDocument();
    expect(document.querySelector("[data-nc-empty]")).not.toBeInTheDocument();
  });

  it("un dossier vide affiche un message", () => {
    show({ isLoading: false, isError: false, isSuccess: true, data: [] });
    expect(document.querySelector("[data-nc-empty]")).toBeInTheDocument();
  });
});
