import { afterEach, beforeEach } from "vitest";
import { cloudDriveAvailable, downloadFromDrive, driveDownloadUrl, importedName, uploadToDrive } from "./cloudDrive";

const doc = (mimeType: string, name = "Mon fichier") => ({ id: "d1", name, mimeType });

describe("cloudDriveAvailable", () => {
  it("activé et configuré seulement", () => {
    expect(cloudDriveAvailable({ enableCloudDrive: true, cloudDriveGoogleClientId: "id" })).toBe(true);
    expect(cloudDriveAvailable({ enableCloudDrive: true, cloudDriveGoogleClientId: "" })).toBe(false);
    expect(cloudDriveAvailable({ enableCloudDrive: false, cloudDriveGoogleClientId: "id" })).toBe(false);
  });
});

describe("formats natifs Google", () => {
  it("un document natif passe par l'export vers un format bureautique", () => {
    expect(driveDownloadUrl(doc("application/vnd.google-apps.document"))).toContain("/d1/export?mimeType=application%2Fvnd.openxmlformats-officedocument.wordprocessingml.document");
    expect(importedName(doc("application/vnd.google-apps.spreadsheet", "Budget"))).toBe("Budget.xlsx");
    expect(importedName(doc("application/vnd.google-apps.presentation", "Cours.pptx"))).toBe("Cours.pptx");
  });
  it("un fichier ordinaire est téléchargé tel quel", () => {
    expect(driveDownloadUrl(doc("application/pdf"))).toBe("https://www.googleapis.com/drive/v3/files/d1?alt=media");
    expect(importedName(doc("application/pdf", "a.pdf"))).toBe("a.pdf");
  });
});

describe("échanges avec Google", () => {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  beforeEach(() => {
    calls.length = 0;
  });
  afterEach(() => vi.unstubAllGlobals());

  it("importe un fichier Drive en File nommé", async () => {
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return new Response(new Blob(["octets"], { type: "application/pdf" }), { status: 200 });
    });
    const file = await downloadFromDrive(doc("application/pdf", "a.pdf"), "jeton");
    expect(file.name).toBe("a.pdf");
    expect((calls[0].init?.headers as Record<string, string>).Authorization).toBe("Bearer jeton");
  });

  it("signale un refus de Google au lieu d'importer un fichier vide", async () => {
    vi.stubGlobal("fetch", async () => new Response("", { status: 403 }));
    await expect(downloadFromDrive(doc("application/pdf"), "jeton")).rejects.toThrow("403");
  });

  it("exporte vers Drive en deux temps : métadonnées avec dossier, puis contenu", async () => {
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      if (url.startsWith("/workspace/document/")) return new Response(new Blob(["abc"], { type: "text/plain" }), { status: 200 });
      if (url.includes("/drive/v3/files?fields=id")) return new Response(JSON.stringify({ id: "nouveau" }), { status: 200 });
      return new Response("", { status: 200 });
    });
    await uploadToDrive("doc1", "a.txt", "jeton", "dossierDrive");
    expect(calls.map((c) => c.url)).toEqual(["/workspace/document/doc1", "https://www.googleapis.com/drive/v3/files?fields=id", "https://www.googleapis.com/upload/drive/v3/files/nouveau?uploadType=media"]);
    expect(JSON.parse(calls[1].init?.body as string)).toEqual({ name: "a.txt", parents: ["dossierDrive"] });
    expect(calls[2].init?.method).toBe("PATCH");
  });

  it("l'envoi du contenu refusé est signalé", async () => {
    vi.stubGlobal("fetch", async (url: string) => {
      if (url.startsWith("/workspace/")) return new Response(new Blob(["x"]), { status: 200 });
      if (url.includes("fields=id")) return new Response(JSON.stringify({ id: "n" }), { status: 200 });
      return new Response("", { status: 500 });
    });
    await expect(uploadToDrive("d", "a", "j")).rejects.toThrow("500");
  });
});
