import { afterEach } from "vitest";
import { commentService, createFolder } from "./workspace.service";

vi.mock("@open-ent/client", () => ({ odeServices: { http: () => ({}) } }));

afterEach(() => vi.unstubAllGlobals());

describe("envoi de formulaires", () => {
  it("la création de dossier envoie un formulaire avec son type de contenu explicite", async () => {
    const calls: Array<{ url: string; init: RequestInit }> = [];
    vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ _id: "n1" }), { status: 201 });
    });
    const res = await createFolder("Cours 6e", "p1");
    expect(res).toEqual({ _id: "n1" });
    expect(calls[0].url).toBe("/workspace/folder");
    expect((calls[0].init.headers as Record<string, string>)["Content-Type"]).toContain("application/x-www-form-urlencoded");
    expect(calls[0].init.body).toBe("name=Cours+6e&parentFolderId=p1");
  });

  it("le commentaire d'un dossier et d'un document vise la bonne route", async () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", async (url: string) => {
      urls.push(url);
      return new Response(JSON.stringify({ id: "c1" }), { status: 200 });
    });
    await commentService.add({ _id: "f1", eType: "folder" }, "bonjour");
    await commentService.add({ _id: "d1", eType: "file" }, "bonjour");
    expect(urls).toEqual(["/workspace/folder/f1/comment", "/workspace/document/d1/comment"]);
  });

  it("l'erreur du serveur est transmise telle quelle (code traduisible), sinon le statut", async () => {
    vi.stubGlobal("fetch", async () => new Response(JSON.stringify({ error: "workspace.folder.already.exists" }), { status: 400 }));
    await expect(createFolder("a")).rejects.toThrow("workspace.folder.already.exists");
    vi.stubGlobal("fetch", async () => new Response("", { status: 500 }));
    await expect(createFolder("a")).rejects.toThrow("500");
  });
});
