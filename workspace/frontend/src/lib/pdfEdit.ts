import type { PDFDocument, PDFFont, PDFPage } from "pdf-lib";
import { PdfLib, loadPdfLibs } from "./pdfLibs";

/**
 * Éditeur PDF de l'espace documentaire : modèle des modifications et écriture du fichier (pdf-lib).
 *
 * Toutes les coordonnées sont en points PDF, dans l'espace de la page d'origine (origine en bas à
 * gauche, sans tenir compte de sa rotation) : l'interface les obtient depuis l'écran par
 * `viewport.convertToPdfPoint` de pdf.js, sur un viewport tourné comme la page finale (sa rotation
 * propre /Rotate plus le quart de tour demandé). Texte et images sont tournés d'autant pour
 * apparaître droits dans cette orientation.
 *
 * pdf-lib n'est importé ici qu'en type : ses valeurs viennent de `loadPdfLibs`, qui télécharge le
 * fichier annexe à l'ouverture de l'éditeur seulement.
 */

export type Point = { x: number; y: number };
export type Color = { r: number; g: number; b: number };

interface Base {
  id: string;
  /** Page d'origine (index à partir de 0 dans le PDF chargé). */
  page: number;
}
/** Zone (coin bas-gauche + taille, en points). */
export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}
export type Annotation =
  | (Base & Box & { kind: "text"; text: string; size: number; color: Color })
  | (Base & Box & { kind: "highlight"; color: Color })
  | (Base & Box & { kind: "rect"; color: Color; width: number })
  | (Base & { kind: "ink"; points: Point[]; color: Color; width: number })
  | (Base & Box & { kind: "image"; png: Uint8Array });

/** Page du document produit : une page d'origine (avec un quart de tour éventuel) ou une page blanche. */
export type PageRef = { source: number; turn: 0 | 90 | 180 | 270 } | { blank: true; width: number; height: number };

export type FieldValue = string | boolean;

export interface PdfField {
  name: string;
  type: "text" | "checkbox" | "dropdown" | "radio" | "list";
  value: FieldValue;
  options?: string[];
  multiline?: boolean;
  readOnly?: boolean;
}

export interface Edits {
  pages: PageRef[];
  annotations: Annotation[];
  fields: Record<string, FieldValue>;
  /** Figer les champs du formulaire dans la page (plus modifiables). */
  flattenForm?: boolean;
}

export const COLORS: Record<string, Color> = {
  black: { r: 0, g: 0, b: 0 },
  blue: { r: 0.1, g: 0.3, b: 0.8 },
  red: { r: 0.85, g: 0.1, b: 0.1 },
  green: { r: 0.1, g: 0.55, b: 0.2 },
  yellow: { r: 1, g: 0.86, b: 0.1 },
};

/** Le PDF est-il chiffré (pdf-lib refuse de l'écrire sans perdre la protection) ? */
export class EncryptedPdfError extends Error {}

export const loadPdf = async (bytes: ArrayBuffer | Uint8Array): Promise<PDFDocument> => {
  const { pdfLib } = await loadPdfLibs();
  try {
    return await pdfLib.PDFDocument.load(bytes, { updateMetadata: false });
  } catch (e) {
    if (e instanceof Error && /encrypt/i.test(e.message)) throw new EncryptedPdfError(e.message);
    throw e;
  }
};

/** Pages d'origine dans leur ordre, sans quart de tour : point de départ de l'éditeur. */
export const initialPages = (count: number): PageRef[] => Array.from({ length: count }, (_, i) => ({ source: i, turn: 0 as const }));

/** Champs de formulaire lisibles et modifiables, avec leur valeur actuelle. */
export const readFields = async (doc: PDFDocument): Promise<PdfField[]> => {
  const { PDFTextField, PDFCheckBox, PDFDropdown, PDFOptionList, PDFRadioGroup } = (await loadPdfLibs()).pdfLib;
  const out: PdfField[] = [];
  for (const f of doc.getForm().getFields()) {
    const name = f.getName();
    const readOnly = f.isReadOnly();
    if (f instanceof PDFTextField) out.push({ name, type: "text", value: f.getText() ?? "", multiline: f.isMultiline(), readOnly });
    else if (f instanceof PDFCheckBox) out.push({ name, type: "checkbox", value: f.isChecked(), readOnly });
    else if (f instanceof PDFDropdown) out.push({ name, type: "dropdown", value: f.getSelected()[0] ?? "", options: f.getOptions(), readOnly });
    else if (f instanceof PDFOptionList) out.push({ name, type: "list", value: f.getSelected()[0] ?? "", options: f.getOptions(), readOnly });
    else if (f instanceof PDFRadioGroup) out.push({ name, type: "radio", value: f.getSelected() ?? "", options: f.getOptions(), readOnly });
  }
  return out;
};

/** Caractères que la police standard ne sait pas écrire (hors WinAnsi) remplacés par « ? ». */
export const encodable = (font: PDFFont, text: string): string => {
  const set = new Set(font.getCharacterSet());
  return [...text].map((c) => (c === "\n" || set.has(c.codePointAt(0) ?? 0) ? c : "?")).join("");
};


/**
 * Point d'ancrage et angle pour qu'un élément apparaisse droit à l'écran sur une page tournée de
 * `rotation` : pdf-lib tourne autour de l'ancre dans le sens trigonométrique, l'affichage tourne la
 * page dans le sens horaire ; l'ancre est le coin de la zone qui se trouve en bas à gauche à l'écran.
 */
const anchor = (box: Box, rotation: number): Point => {
  switch (((rotation % 360) + 360) % 360) {
    case 90:
      return { x: box.x + box.w, y: box.y };
    case 180:
      return { x: box.x + box.w, y: box.y + box.h };
    case 270:
      return { x: box.x, y: box.y + box.h };
    default:
      return { x: box.x, y: box.y };
  }
};

const drawAnnotation = async (lib: PdfLib, doc: PDFDocument, page: PDFPage, a: Annotation, font: PDFFont) => {
  const { degrees } = lib;
  const pdfColor = (c: Color) => lib.rgb(c.r, c.g, c.b);
  const rotation = page.getRotation().angle;
  const sideways = rotation % 180 !== 0;
  switch (a.kind) {
    case "highlight":
      page.drawRectangle({ x: a.x, y: a.y, width: a.w, height: a.h, color: pdfColor(a.color), opacity: 0.35 });
      break;
    case "rect":
      page.drawRectangle({ x: a.x, y: a.y, width: a.w, height: a.h, borderColor: pdfColor(a.color), borderWidth: a.width });
      break;
    case "ink":
      for (let i = 1; i < a.points.length; i++) {
        page.drawLine({ start: a.points[i - 1], end: a.points[i], thickness: a.width, color: pdfColor(a.color), lineCap: 1 });
      }
      break;
    case "text": {
      // première ligne sous le bord haut de la zone (haut à l'écran), lignes suivantes vers le bas
      const lines = encodable(font, a.text).split("\n");
      const lineHeight = a.size * 1.2;
      const o = anchor(a, rotation);
      const height = sideways ? a.w : a.h;
      const rad = (rotation * Math.PI) / 180;
      lines.forEach((line, i) => {
        // décalage « vers le bas de l'écran » exprimé dans le repère tourné
        const down = height - a.size - i * lineHeight;
        page.drawText(line, {
          x: o.x - Math.sin(rad) * down,
          y: o.y + Math.cos(rad) * down,
          size: a.size,
          font,
          color: pdfColor(a.color),
          rotate: degrees(rotation),
        });
      });
      break;
    }
    case "image": {
      const image = await doc.embedPng(a.png);
      const o = anchor(a, rotation);
      page.drawImage(image, {
        x: o.x,
        y: o.y,
        width: sideways ? a.h : a.w,
        height: sideways ? a.w : a.h,
        rotate: degrees(rotation),
      });
      break;
    }
  }
};

const fillFields = (lib: PdfLib, doc: PDFDocument, values: Record<string, FieldValue>) => {
  const { PDFTextField, PDFCheckBox, PDFDropdown, PDFOptionList, PDFRadioGroup } = lib;
  const form = doc.getForm();
  for (const [name, value] of Object.entries(values)) {
    const f = form.getFieldMaybe(name);
    if (!f || f.isReadOnly()) continue;
    if (f instanceof PDFTextField) f.setText(String(value));
    else if (f instanceof PDFCheckBox) (value ? f.check() : f.uncheck());
    else if (f instanceof PDFDropdown || f instanceof PDFOptionList) {
      if (value) f.select(String(value));
      else f.clear();
    } else if (f instanceof PDFRadioGroup) {
      if (value) f.select(String(value));
      else f.clear();
    }
  }
};

/**
 * Applique les modifications et rend le nouveau PDF. Les pages d'origine absentes de `pages` sont
 * supprimées ; l'ordre de `pages` devient celui du document.
 */
export const applyEdits = async (bytes: ArrayBuffer | Uint8Array, edits: Edits): Promise<Uint8Array> => {
  const lib = (await loadPdfLibs()).pdfLib;
  const { degrees } = lib;
  const doc = await loadPdf(bytes);
  const originals = doc.getPages();
  const font = await doc.embedFont(lib.StandardFonts.Helvetica);

  if (Object.keys(edits.fields).length) fillFields(lib, doc, edits.fields);
  if (edits.flattenForm && doc.getForm().getFields().length) doc.getForm().flatten();

  // rotation finale d'abord : l'éditeur montre la page dans cette orientation, et le texte comme les
  // images doivent y apparaître droits
  for (const ref of edits.pages) {
    if ("blank" in ref || !ref.turn) continue;
    const page = originals[ref.source];
    page.setRotation(degrees((page.getRotation().angle + ref.turn) % 360));
  }

  // annotations sur les pages d'origine (avant toute réorganisation)
  for (const a of edits.annotations) {
    const page = originals[a.page];
    if (page) await drawAnnotation(lib, doc, page, a, font);
  }

  // réorganisation : on retire tout, puis on remet dans l'ordre voulu
  for (let i = doc.getPageCount() - 1; i >= 0; i--) doc.removePage(i);
  edits.pages.forEach((ref, i) => {
    if ("blank" in ref) doc.insertPage(i, [ref.width, ref.height]);
    else doc.insertPage(i, originals[ref.source]);
  });
  if (!doc.getPageCount()) throw new Error("workspace.pdfeditor.no.page");
  return doc.save();
};

/** Zone normalisée à partir de deux coins quelconques. */
export const boxFrom = (a: Point, b: Point): Box => ({
  x: Math.min(a.x, b.x),
  y: Math.min(a.y, b.y),
  w: Math.abs(a.x - b.x),
  h: Math.abs(a.y - b.y),
});

/** Déplace une page d'un cran (sens -1 : vers le début). */
export const movePage = (pages: PageRef[], index: number, delta: -1 | 1): PageRef[] => {
  const j = index + delta;
  if (j < 0 || j >= pages.length) return pages;
  const next = [...pages];
  [next[index], next[j]] = [next[j], next[index]];
  return next;
};

/** Quart de tour supplémentaire (sens horaire) d'une page d'origine. */
export const turnPage = (pages: PageRef[], index: number): PageRef[] =>
  pages.map((p, i) => (i === index && !("blank" in p) ? { ...p, turn: (((p.turn + 90) % 360) as 0 | 90 | 180 | 270) } : p));
