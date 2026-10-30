import { fireEvent, render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { NextcloudBrowser } from "./NextcloudBrowser";
import { SyncDocument } from "../lib/nextcloud";

vi.mock("@open-ent/react", () => ({
  AppHeader: ({ children }: { children: unknown }) => <div>{children as never}</div>,
  LoadingScreen: () => <div data-loading />,
  useEdificeClient: () => ({ user: { userId: "u1", username: "Lilit" } }),
  SegmentedControl: ({ options, value, onChange }: { options: Array<{ label: string; value: string }>; value: string; onChange: (v: string) => void }) => (
    <div>
      {options.map((o) => (
        <button key={o.value} type="button" data-view={o.value} aria-pressed={o.value === value} onClick={() => onChange(o.value)} />
      ))}
    </div>
  ),
  ShareModal: ({ shareOptions, onCancel }: { shareOptions: { resourceId: string }; onCancel: () => void }) => (
    <div data-share-modal={shareOptions.resourceId}>
      <button type="button" data-share-cancel onClick={onCancel} />
    </div>
  ),
}));
vi.mock("../components/ConfirmModal", () => ({
  ConfirmModal: ({ isOpen, onConfirm }: { isOpen: boolean; onConfirm: () => void }) => (isOpen ? <button type="button" data-confirm-submit onClick={onConfirm} /> : null),
}));
vi.mock("../services/workspace.service", () => ({ elementActions: { shareLike: vi.fn() }, workspaceService: { savePreference: (p: unknown) => prefs.save(p) } }));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock("../components/FolderPicker", () => ({ FolderPicker: () => null }));
const svc = vi.hoisted(() => ({ toWorkspace: vi.fn(), fromWorkspace: vi.fn(), move: vi.fn() }));
const prefs = vi.hoisted(() => ({ value: {} as Record<string, unknown>, save: vi.fn() }));
vi.mock("../services/nextcloud.service", () => ({ nextcloudService: svc }));

const state = vi.hoisted(() => ({ list: {} as Record<string, unknown> }));
vi.mock("../services/queries", () => ({
  useNextcloudList: () => state.list,
  useNextcloudUrl: () => ({ data: { url: "https://nc.example", hidden: false } }),
  useNextcloudQuota: () => ({ data: { used: 10, total: 100 } }),
  useFolderStore: () => ({ lazy: false, isLoaded: () => false, loadChildren: async () => undefined, ensureFolder: async () => undefined }),
  useFolders: () => ({ data: [] }),
  useQuota: () => ({ data: { storage: 5, quota: 50 } }),
  useWorkspacePreference: () => ({ data: prefs.value }),
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
    expect(document.querySelector('[data-nc-action="create-document"]')).toBeInTheDocument();
    // les deux jauges : espace des documents personnels et espace synchronisé NextCloud
    expect(document.querySelector("[data-workspace-quota]")).toBeInTheDocument();
    expect(document.querySelector("[data-nc-quota]")).toBeInTheDocument();
  });

  it("créer un document : choix du type, nom obligatoire", () => {
    show({ isLoading: false, isError: false, isSuccess: true, data: [] });
    fireEvent.click(document.querySelector('[data-nc-action="create-document"]')!);
    expect(document.querySelector("[data-nc-create-dialog]")).toBeInTheDocument();
    expect([...document.querySelectorAll("[data-nc-new-type]")].map((r) => r.getAttribute("data-nc-new-type"))).toEqual(["docx", "xlsx", "pptx"]);
    expect((document.querySelector("[data-nc-create-submit]") as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(document.querySelector("#nc-new-name")!, { target: { value: "Devoir" } });
    expect((document.querySelector("[data-nc-create-submit]") as HTMLButtonElement).disabled).toBe(false);
  });

  it("la sélection d'un fichier propose les actions NextCloud", () => {
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({})] });
    fireEvent.click(document.querySelector("[data-nc-select]")!);
    // actions fréquentes en boutons, transferts vers l'espace documentaire dans le menu « Organiser », corbeille en dernier
    expect(actions()).toEqual(["download", "rename", "share", "trash"]);
    expect(document.querySelector('[data-menu-toggle="organise"]')?.getAttribute("data-menu-actions")).toBe("copyToWorkspace moveToWorkspace");
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

  it("partager un fichier NextCloud : un exemplaire est placé dans l'espace documentaire puis partagé", async () => {
    svc.toWorkspace.mockResolvedValue([{ _id: "copie1", name: "a.txt", eType: "file", owner: "u1" }]);
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({})] });
    fireEvent.click(document.querySelector("[data-nc-select]")!);
    fireEvent.click(document.querySelector('[data-nc-action="share"]')!);
    fireEvent.click(document.querySelector("[data-confirm-submit]")!);
    await vi.waitFor(() => expect(document.querySelector('[data-share-modal="copie1"]')).toBeInTheDocument());
    expect(svc.toWorkspace).toHaveBeenCalledWith("u1", "copy", ["a.txt"]);
  });

  it("annuler le partage remet l'exemplaire dans NextCloud", async () => {
    svc.toWorkspace.mockResolvedValue([{ _id: "copie1", name: "a.txt", eType: "file", owner: "u1" }]);
    svc.fromWorkspace.mockResolvedValue(undefined);
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({})] });
    fireEvent.click(document.querySelector("[data-nc-select]")!);
    fireEvent.click(document.querySelector('[data-nc-action="share"]')!);
    fireEvent.click(document.querySelector("[data-confirm-submit]")!);
    await vi.waitFor(() => expect(document.querySelector("[data-share-modal]")).toBeInTheDocument());
    fireEvent.click(document.querySelector("[data-share-cancel]")!);
    await vi.waitFor(() => expect(svc.fromWorkspace).toHaveBeenCalledWith("u1", "move", ["copie1"], undefined));
  });

  it("propose d'ouvrir NextCloud (connexion unique) et rappelle l'adresse pour les autres appareils", () => {
    show({ isLoading: false, isError: false, isSuccess: true, data: [] });
    const button = document.querySelector("[data-nc-open-server]") as HTMLAnchorElement;
    expect(button.getAttribute("href")).toBe("https://nc.example");
    expect(button.getAttribute("target")).toBe("_blank");
    expect(document.querySelector("[data-nc-url]")?.textContent).toContain("https://nc.example");
  });

  it("affiche les éléments en icônes par défaut, bascule en liste et mémorise le choix", () => {
    prefs.value = {};
    prefs.save.mockClear();
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({ path: "Cours", name: "Cours", isFolder: true, kind: "folder" }), d({})] });
    expect(document.querySelector('[data-nc-list="icons"]')).toBeInTheDocument();
    expect(document.querySelectorAll('[data-nc-list="icons"] [data-nc-item]')).toHaveLength(2);
    expect(document.querySelector("table[data-nc-list]")).toBeNull();
    fireEvent.click(document.querySelector('[data-view="list"]')!);
    expect(document.querySelector("table[data-nc-list]")).toBeInTheDocument();
    expect(prefs.save).toHaveBeenLastCalledWith({ view: "list" });
  });

  it("propose le tri aussi en icônes : critère puis sens", () => {
    prefs.value = {};
    prefs.save.mockClear();
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({ path: "a.txt", name: "a.txt", size: 5 }), d({ path: "b.txt", name: "b.txt", size: 50 })] });
    fireEvent.change(document.querySelector("[data-nc-sort]")!, { target: { value: "size" } });
    expect(prefs.save).toHaveBeenLastCalledWith({ sortField: "metadata.size", sortDesc: false });
    fireEvent.click(document.querySelector("[data-nc-sort-direction]")!);
    expect(prefs.save).toHaveBeenLastCalledWith({ sortField: "metadata.size", sortDesc: true });
    expect([...document.querySelectorAll("[data-nc-item]")].map((n) => n.getAttribute("data-nc-path"))).toEqual(["b.txt", "a.txt"]);
  });

  it("reprend l'affichage en liste mémorisé dans la préférence", () => {
    prefs.value = { view: "list" };
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({})] });
    expect(document.querySelector("table[data-nc-list]")).toBeInTheDocument();
  });

  it("trie par colonne, mémorise le choix et inverse le sens au second clic", () => {
    prefs.value = { view: "list" };
    prefs.save.mockClear();
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({ path: "a.txt", name: "a.txt", size: 5 }), d({ path: "b.txt", name: "b.txt", size: 50 })] });
    const names = () => [...document.querySelectorAll("[data-nc-item]")].map((n) => n.getAttribute("data-nc-path"));
    expect(names()).toEqual(["a.txt", "b.txt"]);
    fireEvent.click(document.querySelector('[data-nc-sort-column="size"]')!);
    expect(names()).toEqual(["a.txt", "b.txt"]);
    expect(prefs.save).toHaveBeenLastCalledWith({ sortField: "metadata.size", sortDesc: false });
    fireEvent.click(document.querySelector('[data-nc-sort-column="size"]')!);
    expect(names()).toEqual(["b.txt", "a.txt"]);
    expect(prefs.save).toHaveBeenLastCalledWith({ sortField: "metadata.size", sortDesc: true });
  });

  it("reprend le tri mémorisé dans la préférence", () => {
    prefs.value = { sortField: "name", sortDesc: true };
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({ path: "a.txt", name: "a.txt" }), d({ path: "b.txt", name: "b.txt" })] });
    expect([...document.querySelectorAll("[data-nc-item]")].map((n) => n.getAttribute("data-nc-path"))).toEqual(["b.txt", "a.txt"]);
    prefs.value = {};
  });

  it("affiche le propriétaire renvoyé par NextCloud", () => {
    prefs.value = { view: "list" };
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({ ownerName: "Alice" })] });
    expect(document.querySelector("[data-nc-item]")?.textContent).toContain("Alice");
  });

  it("lâcher un fichier sur un dossier le déplace dans ce dossier", async () => {
    svc.move.mockResolvedValue(undefined);
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({ path: "Archives", name: "Archives", isFolder: true, kind: "folder" }), d({ path: "a.txt", name: "a.txt" })] });
    const file = document.querySelector('[data-nc-path="a.txt"]')!;
    const folder = document.querySelector('[data-nc-path="Archives"]')!;
    const dataTransfer = { types: ["application/x-nextcloud-paths"], setData: vi.fn(), effectAllowed: "" };
    fireEvent.dragStart(file, { dataTransfer });
    fireEvent.dragOver(folder, { dataTransfer });
    fireEvent.drop(folder, { dataTransfer });
    await vi.waitFor(() => expect(svc.move).toHaveBeenCalledWith("u1", "a.txt", "Archives/a.txt"));
  });

  it("un dossier ne se dépose pas sur lui-même", () => {
    svc.move.mockClear();
    show({ isLoading: false, isError: false, isSuccess: true, data: [d({ path: "Archives", name: "Archives", isFolder: true, kind: "folder" })] });
    const folder = document.querySelector('[data-nc-path="Archives"]')!;
    const dataTransfer = { types: ["application/x-nextcloud-paths"], setData: vi.fn(), effectAllowed: "" };
    fireEvent.dragStart(folder, { dataTransfer });
    fireEvent.drop(folder, { dataTransfer });
    expect(svc.move).not.toHaveBeenCalled();
  });
});
