import { canOpenInGeogebra, canOpenInLool, canOpenInScratch, geogebraUrl, loolCreateUrl, loolReaction, parseMessage } from "./integrations";

const file = (name: string, type?: string) => ({ name, metadata: type ? { "content-type": type } : undefined });
const libre = { provider: "LibreOfficeOnline", capabilities: [{ "content-type": "application/vnd.oasis.opendocument.text" }] };
const only = { provider: "OnlyOffice", capabilities: [{ "content-type": "application/pdf" }] };

describe("canOpenInLool", () => {
  it("LibreOffice Online : un type pris en charge", () => expect(canOpenInLool(libre, file("a.odt", "application/vnd.oasis.opendocument.text"))).toBe(true));
  it("LibreOffice Online : refuse les formats de dessin même reconnus", () => {
    const ctx = { provider: "LibreOfficeOnline", capabilities: [{ "content-type": "application/pdf" }] };
    expect(canOpenInLool(ctx, file("a.pdf", "application/pdf"))).toBe(false);
  });
  it("OnlyOffice : suffit que le type soit pris en charge", () => expect(canOpenInLool(only, file("a.pdf", "application/pdf"))).toBe(true));
  it("sans contexte ou sans type, non", () => {
    expect(canOpenInLool(undefined, file("a.odt", "x"))).toBe(false);
    expect(canOpenInLool(libre, file("a.odt"))).toBe(false);
    expect(canOpenInLool(libre, file("a.odt", "text/plain"))).toBe(false);
  });
});

describe("Scratch et GeoGebra", () => {
  it("projets Scratch", () => {
    expect(canOpenInScratch(file("jeu.sb3", "application/octet-stream"))).toBe(true);
    expect(canOpenInScratch(file("jeu.sb3", "text/plain"))).toBe(false);
    expect(canOpenInScratch(file("jeu.zip", "application/octet-stream"))).toBe(false);
  });
  it("figures GeoGebra", () => {
    expect(canOpenInGeogebra({ name: "figure.GGB" })).toBe(true);
    expect(canOpenInGeogebra({ name: "figure.png" })).toBe(false);
    expect(geogebraUrl({ _id: "i1", name: "ma figure.ggb" })).toBe("/geogebra#/i1?fileName=ma%20figure.ggb");
  });
});

describe("fenêtre de création", () => {
  it("adresse avec ou sans dossier", () => {
    expect(loolCreateUrl()).toBe("/lool/modal/create");
    expect(loolCreateUrl("f 1")).toBe("/lool/modal/create?folderId=f%201");
  });
  it("messages reconnus, les autres ignorés", () => {
    expect(loolReaction({ id: "lool@getFolder" })).toBe("getFolder");
    expect(loolReaction({ id: "lool@resync" })).toBe("resync");
    expect(loolReaction({ id: "lool@close-modal" })).toBe("close");
    expect(loolReaction({ id: "lool@modal-ready" })).toBe("ready");
    expect(loolReaction({ id: "autre" })).toBeNull();
    expect(loolReaction(null)).toBeNull();
  });
  it("lit un message JSON ou objet, ignore l'illisible", () => {
    expect(parseMessage('{"id":"lool@resync"}')).toEqual({ id: "lool@resync" });
    expect(parseMessage({ id: "x" })).toEqual({ id: "x" });
    expect(parseMessage("{pas json")).toBeNull();
  });
});
