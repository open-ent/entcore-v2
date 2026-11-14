import { PDFDocument, StandardFonts, degrees } from "pdf-lib";
import { readFileSync } from "fs";
import { join } from "path";
import * as pdfLib from "pdf-lib";
import { setPdfLibs } from "./pdfLibs";
import { applyEdits, boxFrom, COLORS, EncryptedPdfError, encodable, initialPages, loadPdf, movePage, readFields, turnPage } from "./pdfEdit";

// pdf-lib importé directement : les tests ne passent pas par le fichier annexe
setPdfLibs({ pdfLib, pdfjs: {} as never });

// PNG 1×1 rouge
const PNG = Uint8Array.from(atob("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="), (c) => c.charCodeAt(0));

/** Trois pages A4 (la troisième déjà tournée de 90°) et un champ texte sur la première. */
const sample = async () => {
  const doc = await PDFDocument.create();
  const font = await doc.embedFont(StandardFonts.Helvetica);
  for (let i = 1; i <= 3; i++) {
    const page = doc.addPage([595, 842]);
    page.drawText(`Page ${i}`, { x: 72, y: 760, size: 24, font });
    if (i === 3) page.setRotation(degrees(90));
  }
  const field = doc.getForm().createTextField("nom");
  field.addToPage(doc.getPage(0), { x: 72, y: 600, width: 200, height: 24 });
  return doc.save();
};

describe("applyEdits", () => {
  it("réordonne, tourne, ajoute une page blanche et supprime les pages absentes", async () => {
    const out = await applyEdits(await sample(), {
      pages: [{ source: 2, turn: 90 }, { blank: true, width: 595, height: 842 }, { source: 0, turn: 0 }],
      annotations: [],
      fields: {},
    });
    const doc = await PDFDocument.load(out);
    expect(doc.getPageCount()).toBe(3);
    expect(doc.getPage(0).getRotation().angle).toBe(180); // 90 d'origine + un quart de tour
    expect(doc.getPage(1).getSize()).toEqual({ width: 595, height: 842 });
    expect(doc.getPage(2).getRotation().angle).toBe(0);
  });

  it("remplit le formulaire, et le fige sur demande", async () => {
    const filled = await PDFDocument.load(await applyEdits(await sample(), { pages: initialPages(3), annotations: [], fields: { nom: "Camille Martin" } }));
    expect(await readFields(filled)).toEqual([{ name: "nom", type: "text", value: "Camille Martin", multiline: false, readOnly: false }]);
    const flat = await PDFDocument.load(
      await applyEdits(await sample(), { pages: initialPages(3), annotations: [], fields: { nom: "Camille" }, flattenForm: true }),
    );
    expect(flat.getForm().getFields()).toHaveLength(0);
  });

  it("dessine texte, surlignage, cadre, trait et image dans le contenu des pages", async () => {
    const src = await sample();
    const before = (await PDFDocument.load(src)).getPage(0).node.Contents()?.toString().length ?? 0;
    const out = await applyEdits(src, {
      pages: initialPages(3),
      fields: {},
      annotations: [
        { id: "t", page: 0, kind: "text", x: 72, y: 500, w: 200, h: 40, text: "Très bien\nà revoir", size: 12, color: COLORS.red },
        { id: "h", page: 0, kind: "highlight", x: 70, y: 750, w: 120, h: 30, color: COLORS.yellow },
        { id: "r", page: 0, kind: "rect", x: 60, y: 400, w: 100, h: 50, color: COLORS.blue, width: 2 },
        { id: "i", page: 0, kind: "ink", points: [{ x: 10, y: 10 }, { x: 50, y: 60 }, { x: 90, y: 20 }], color: COLORS.black, width: 2 },
        { id: "s", page: 2, kind: "image", x: 300, y: 100, w: 120, h: 60, png: PNG },
      ],
    });
    const doc = await PDFDocument.load(out);
    expect(doc.getPageCount()).toBe(3);
    // pdf-lib ajoute un flux de contenu par page modifiée
    const after = doc.getPage(0).node.Contents()?.toString().length ?? 0;
    expect(after).toBeGreaterThan(before);
  });

  it("refuse un document sans aucune page", async () => {
    await expect(applyEdits(await sample(), { pages: [], annotations: [], fields: {} })).rejects.toThrow("workspace.pdfeditor.no.page");
  });
});

describe("outils de l'éditeur", () => {
  it("caractères hors de la police standard remplacés", async () => {
    const font = await (await PDFDocument.create()).embedFont(StandardFonts.Helvetica);
    expect(encodable(font, "Élève « très » bien 👍\nfin")).toBe("Élève « très » bien ?\nfin");
  });
  it("zone normalisée, déplacement et rotation de pages", () => {
    expect(boxFrom({ x: 50, y: 10 }, { x: 10, y: 40 })).toEqual({ x: 10, y: 10, w: 40, h: 30 });
    const pages = initialPages(3);
    expect(movePage(pages, 0, 1).map((p) => ("source" in p ? p.source : -1))).toEqual([1, 0, 2]);
    expect(movePage(pages, 0, -1)).toBe(pages);
    expect(turnPage(turnPage(turnPage(turnPage(pages, 1), 1), 1), 1)[1]).toEqual({ source: 1, turn: 0 });
  });
  it("chargement : un PDF chiffré est signalé comme tel", async () => {
    // chiffré par qpdf (AES-256, mot de passe « secret ») : pdf-lib ne sait pas l'écrire
    const locked = Uint8Array.from(readFileSync(join(__dirname, "../test/fixtures/locked.pdf")));
    await expect(loadPdf(locked)).rejects.toBeInstanceOf(EncryptedPdfError);
  });
});
