import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ExportTab } from "./ExportTab";
import { ImportTab } from "./ImportTab";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string, o?: { defaultValue?: string }) => o?.defaultValue ?? k }) }));

const svc = vi.hoisted(() => ({
  exportableApps: vi.fn(),
  myApps: vi.fn(),
  exportExists: vi.fn(),
  startExport: vi.fn(),
  verifyExport: vi.fn(),
  downloadUrl: (id: string) => `/archive/export/${id}`,
  upload: vi.fn(),
  analyze: vi.fn(),
  cancelImport: vi.fn(),
  launch: vi.fn(),
}));
vi.mock("../services/archive.service", () => ({
  archiveService: svc,
  HttpError: class HttpError extends Error {
    constructor(public status: number) {
      super(`HTTP ${status}`);
    }
  },
}));

const wrap = (ui: React.ReactNode) => render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{ui}</QueryClientProvider>);

beforeEach(() => {
  Object.values(svc).forEach((f) => typeof f === "function" && "mockReset" in f && (f as ReturnType<typeof vi.fn>).mockReset());
  svc.exportExists.mockResolvedValue(false);
});

describe("ExportTab", () => {
  it("liste les applications de l'usager ; exporter n'est possible qu'avec une sélection", async () => {
    svc.exportableApps.mockResolvedValue(["blog", "wiki", "rack"]);
    svc.myApps.mockResolvedValue([{ prefix: "/blog" }, { prefix: "/wiki" }]);
    svc.startExport.mockResolvedValue({ exportId: "e1" });
    const { container } = wrap(<ExportTab />);
    await waitFor(() => expect(container.querySelector('[data-export-app="blog"]')).not.toBeNull());
    expect(container.querySelector('[data-export-app="rack"]')).toBeNull();
    const launch = container.querySelector("[data-export-launch]") as HTMLButtonElement;
    expect(launch.disabled).toBe(true);
    fireEvent.click(container.querySelector('[data-export-app="wiki"]')!);
    expect(launch.disabled).toBe(false);
    fireEvent.click(launch);
    await waitFor(() => expect(svc.startExport).toHaveBeenCalledWith(["wiki"]));
  });

  it("« Tout » coche et décoche toutes les applications", async () => {
    svc.exportableApps.mockResolvedValue(["blog", "wiki"]);
    svc.myApps.mockResolvedValue([{ prefix: "/blog" }, { prefix: "/wiki" }]);
    const { container } = wrap(<ExportTab />);
    await waitFor(() => expect(container.querySelector("[data-export-all]")).not.toBeNull());
    fireEvent.click(container.querySelector("[data-export-all]")!);
    expect((container.querySelector('[data-export-app="blog"]') as HTMLInputElement).checked).toBe(true);
    fireEvent.click(container.querySelector("[data-export-all]")!);
    expect((container.querySelector('[data-export-app="blog"]') as HTMLInputElement).checked).toBe(false);
  });

  it("compte dont les applications sont retirées : documents seulement ou tout", async () => {
    svc.exportableApps.mockResolvedValue(["blog", "workspace", "rack"]);
    svc.myApps.mockResolvedValue([]);
    svc.startExport.mockResolvedValue({ exportId: "e2" });
    const { container } = wrap(<ExportTab />);
    await waitFor(() => expect(container.querySelector("[data-export-docs]")).not.toBeNull());
    expect(container.querySelector("[data-export-launch]")).toBeNull();
    fireEvent.click(container.querySelector("[data-export-docs]")!);
    await waitFor(() => expect(svc.startExport).toHaveBeenCalledWith(["workspace", "rack"]));
  });

  it("un export déjà prêt est signalé, sans proposer d'en lancer un autre", async () => {
    svc.exportableApps.mockResolvedValue(["blog"]);
    svc.myApps.mockResolvedValue([{ prefix: "/blog" }]);
    svc.exportExists.mockResolvedValue(true);
    const { container } = wrap(<ExportTab />);
    await waitFor(() => expect(container.querySelector("[data-export-waiting]")?.textContent).toBe("archive.exists"));
    expect(container.querySelector("[data-export-launch]")).toBeNull();
  });
});

describe("ImportTab", () => {
  const file = () => new File(["zip"], "archive.zip", { type: "application/zip" });
  const choose = async (container: HTMLElement) => {
    const input = container.querySelector("[data-import-input]") as HTMLInputElement;
    await act(async () => {
      fireEvent.change(input, { target: { files: [file()] } });
    });
  };

  it("envoie, analyse puis propose les applications avec le quota ; le dépassement bloque l'import", async () => {
    svc.upload.mockReturnValue({ promise: Promise.resolve({ importId: "i1" }), abort: vi.fn() });
    svc.analyze.mockResolvedValue({ quota: 1000, apps: { blog: { size: 400 }, wiki: { size: 900 } } });
    const { container } = wrap(<ImportTab />);
    await choose(container);
    await waitFor(() => expect(container.querySelector("[data-import-analysis]")).not.toBeNull());
    const launch = container.querySelector("[data-import-launch]") as HTMLButtonElement;
    expect(launch.disabled).toBe(true);
    fireEvent.click(container.querySelector('[data-import-app="blog"]')!);
    expect(launch.disabled).toBe(false);
    fireEvent.click(container.querySelector('[data-import-app="wiki"]')!);
    // 400 + 900 > 1000 : quota dépassé
    expect(launch.disabled).toBe(true);
    expect(container.querySelector("[data-import-quota-selected]")?.textContent).toContain("archive.quota.warning");
  });

  it("importe les applications cochées et affiche le rapport", async () => {
    svc.upload.mockReturnValue({ promise: Promise.resolve({ importId: "i2" }), abort: vi.fn() });
    svc.analyze.mockResolvedValue({ quota: 10_000, apps: { blog: { size: 400 }, actualites: { size: 10 } } });
    svc.launch.mockResolvedValue({ blog: { resourcesNumber: 3, duplicatesNumber: 1, errorsNumber: 0 }, actualites: { resourcesNumber: 2, duplicatesNumber: 5, errorsNumber: 1 } });
    const { container } = wrap(<ImportTab />);
    await choose(container);
    await waitFor(() => expect(container.querySelector("[data-import-analysis]")).not.toBeNull());
    fireEvent.click(container.querySelector("[data-import-all]")!);
    fireEvent.click(container.querySelector("[data-import-launch]")!);
    await waitFor(() => expect(container.querySelector("[data-import-report]")).not.toBeNull());
    expect(svc.launch).toHaveBeenCalledWith("i2", { blog: { size: 400 }, actualites: { size: 10 } });
    const blog = container.querySelector('[data-report-app="blog"]')!.textContent!;
    expect(blog).toContain("3");
    // actualités : doublons non dénombrés → « non applicable »
    expect(container.querySelector('[data-report-app="actualites"]')!.textContent).toContain("archive.import.na");
  });

  it("archive vide ou illisible : message et retour au choix du fichier", async () => {
    svc.upload.mockReturnValue({ promise: Promise.resolve({ importId: "i3" }), abort: vi.fn() });
    svc.analyze.mockResolvedValue({ quota: 100, apps: {} });
    const { container } = wrap(<ImportTab />);
    await choose(container);
    await waitFor(() => expect(container.querySelector("[data-archive-notice]")?.textContent).toBe("archive.import.none.available"));
    expect(container.querySelector("[data-import-dropzone]")).not.toBeNull();
    expect(svc.cancelImport).toHaveBeenCalledWith("i3");
  });

  it("une archive trop volumineuse (413) est signalée", async () => {
    const { HttpError } = await import("../services/archive.service");
    svc.upload.mockReturnValue({ promise: Promise.reject(new HttpError(413)), abort: vi.fn() });
    const { container } = wrap(<ImportTab />);
    await choose(container);
    await waitFor(() => expect(container.querySelector("[data-archive-notice]")?.textContent).toBe("archive.import.toobig"));
    expect(screen.queryByText("archive.import.loading")).toBeNull();
  });
});
