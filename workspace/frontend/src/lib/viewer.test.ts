import { detectDelimiter, parseCsv, previewKind, previewUrlFor } from "./viewer";

describe("previewKind", () => {
  it.each([
    ["image/png", "a.png", "image"],
    ["audio/mpeg", "a.mp3", "audio"],
    ["video/mp4", "a.mp4", "video"],
    ["application/pdf", "a.pdf", "pdf"],
    ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "a.xlsx", "table"],
    ["text/csv", "a.csv", "table"],
    ["application/vnd.openxmlformats-officedocument.presentationml.presentation", "a.pptx", "office"],
    ["application/octet-stream", "devoir.docx", "office"],
    ["text/html", "a.html", "html"],
    ["text/plain", "a.txt", "text"],
    ["application/zip", "a.zip", "none"],
    [undefined, "inconnu", "none"],
  ])("%s %s → %s", (mime, name, kind) => expect(previewKind(mime as string | undefined, name)).toBe(kind));
});

describe("previewUrlFor", () => {
  const el = (name: string, type: string) => ({ _id: "i1", name, metadata: { "content-type": type } });
  it("utilise l'aperçu converti pour la bureautique et les tableurs", () => {
    expect(previewUrlFor(el("a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))).toBe("/workspace/document/preview/i1");
    expect(previewUrlFor(el("a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))).toBe("/workspace/document/preview/i1");
  });
  it("lit directement un CSV, une image ou un texte", () => {
    expect(previewUrlFor(el("a.csv", "text/csv"))).toBe("/workspace/document/i1");
    expect(previewUrlFor(el("a.png", "image/png"))).toBe("/workspace/document/i1");
  });
});

describe("parseCsv", () => {
  it("détecte le séparateur", () => {
    expect(detectDelimiter("a;b;c\n1;2;3")).toBe(";");
    expect(detectDelimiter("a,b\n1,2")).toBe(",");
    expect(detectDelimiter("a\tb")).toBe("\t");
    expect(detectDelimiter("seul")).toBe(",");
  });
  it("lit les guillemets et les retours à la ligne dans un champ", () => {
    expect(parseCsv('a;"b;c"\n1;2')).toEqual([["a", "b;c"], ["1", "2"]]);
    expect(parseCsv('x,"ligne1\nligne2","il dit ""oui"""')).toEqual([["x", "ligne1\nligne2", 'il dit "oui"']]);
  });
  it("accepte les fins de ligne Windows et ignore les lignes vides", () => {
    expect(parseCsv("a,b\r\n\r\n1,2\r\n")).toEqual([["a", "b"], ["1", "2"]]);
  });
});
