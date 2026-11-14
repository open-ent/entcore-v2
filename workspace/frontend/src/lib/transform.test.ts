import { availableActions } from "./actions";
import { availableTransforms, baseName, conversionTargets, extensionOf, pagesSelection, passwordStrength, suggestedName } from "./transform";
import { WorkspaceElement } from "./types";

const file = (name: string, id = name): WorkspaceElement => ({ _id: id, name, eType: "file", eParent: null, owner: "u", metadata: { filename: name } as WorkspaceElement["metadata"] });

describe("availableTransforms", () => {
  it("un PDF : pages, protection, aplatissement, non copiable, compression, images, conversion", () => {
    expect(availableTransforms([file("a.pdf")])).toEqual(["convert", "pdfPages", "protectPdf", "unlockPdf", "flattenPdf", "rasterizePdf", "compressPdf", "pdfToImages"]);
  });
  it("plusieurs PDF : fusion seulement", () => expect(availableTransforms([file("a.pdf"), file("b.pdf")])).toEqual(["mergePdf"]));
  it("un document Office : en PDF et vers les autres formats", () => {
    expect(availableTransforms([file("a.docx")])).toEqual(["toPdf", "convert"]);
    expect(availableTransforms([file("a.XLSX")])).toEqual(["toPdf", "convert"]);
  });
  it("des images : assemblage en PDF", () => expect(availableTransforms([file("a.png"), file("b.JPG")])).toEqual(["imagesToPdf"]));
  it("un mélange, un dossier ou un format inconnu : rien", () => {
    expect(availableTransforms([file("a.pdf"), file("b.png")])).toEqual([]);
    expect(availableTransforms([{ ...file("d"), eType: "folder" }])).toEqual([]);
    expect(availableTransforms([file("a.zip")])).toEqual([]);
  });
  it("modifier le PDF : un seul PDF, avec droit de contribution", () => {
    expect(availableActions("owner", [file("a.pdf")])).toContain("editPdf");
    expect(availableActions("owner", [file("a.pdf"), file("b.pdf")])).not.toContain("editPdf");
    expect(availableActions("owner", [file("a.docx")])).not.toContain("editPdf");
    expect(availableActions("shared", [file("a.pdf")])).not.toContain("editPdf");
    expect(availableActions("shared", [file("a.pdf")], { contribOnAll: true })).toContain("editPdf");
    expect(availableActions("trash", [file("a.pdf")])).not.toContain("editPdf");
  });
  it("dans la barre : hors corbeille et seulement avec le droit d'ajouter", () => {
    expect(availableActions("owner", [file("a.pdf")])).toContain("protectPdf");
    expect(availableActions("trash", [file("a.pdf")])).not.toContain("protectPdf");
    expect(availableActions("owner", [file("a.pdf")], { canTransform: false })).not.toContain("protectPdf");
  });
});

describe("formats et noms", () => {
  it("cibles de conversion par famille", () => {
    expect(conversionTargets("docx")).toEqual(["pdf", "odt"]);
    expect(conversionTargets("ods")).toEqual(["pdf", "xlsx", "csv"]);
    expect(conversionTargets("pdf")).toEqual(["docx", "odt"]);
    expect(conversionTargets("exe")).toEqual([]);
  });
  it("extension du fichier téléversé, sinon du nom", () => {
    expect(extensionOf({ name: "Sans extension", metadata: { filename: "x.PDF" } as WorkspaceElement["metadata"] })).toBe("pdf");
    expect(extensionOf({ name: "y.odt" })).toBe("odt");
    expect(baseName({ name: "Sujet v1.2.pdf" })).toBe("Sujet v1.2");
  });
  it("nom proposé pour une fusion", () => {
    const t = (k: string, o?: Record<string, unknown>) => `${k}:${JSON.stringify(o)}`;
    expect(suggestedName("mergePdf", [file("a.pdf"), file("b.pdf")], t)).toBe('workspace.transform.name.merge2:{"a":"a","b":"b"}');
    expect(suggestedName("toPdf", [file("Sujet.docx")], t)).toBe("Sujet");
  });
});

describe("saisies", () => {
  it("sélection de pages", () => {
    expect(pagesSelection("1-3, 7, 10-fin")).toBe("1-3,7,10-z");
    expect(pagesSelection("2")).toBe("2");
    expect(pagesSelection("1;rm")).toBeNull();
    expect(pagesSelection("")).toBeNull();
  });
  it("robustesse du mot de passe", () => {
    expect(passwordStrength("abc")).toBe(0);
    expect(passwordStrength("abcdefgh")).toBe(1);
    expect(passwordStrength("abcdefgh12")).toBe(2);
    expect(passwordStrength("Abcdefgh12!xyz")).toBe(3);
  });
});
