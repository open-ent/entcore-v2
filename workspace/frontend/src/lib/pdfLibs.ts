import type * as PdfLibModule from "pdf-lib";
import type * as PdfjsModule from "pdfjs-dist";

export type PdfLib = typeof PdfLibModule;
export type Pdfjs = typeof PdfjsModule;
export interface PdfLibs {
  pdfLib: PdfLib;
  pdfjs: Pdfjs;
}

/** Fichier annexe produit par `vite build -c vite.pdf-libs.config.ts` (voir src/vendor/pdf-libs.ts). */
export const PDF_LIBS_URL = "/workspace/public/react/pdf-libs.js";

let loading: Promise<PdfLibs> | null = null;

/** pdf-lib et pdf.js, téléchargés une fois, à la première demande. */
export const loadPdfLibs = (): Promise<PdfLibs> => {
  loading ??= (import(/* @vite-ignore */ PDF_LIBS_URL) as Promise<PdfLibs>).catch((e) => {
    loading = null;
    throw e;
  });
  return loading;
};

/** Tests : fournit les bibliothèques sans passer par le fichier annexe. */
export const setPdfLibs = (libs: PdfLibs) => {
  loading = Promise.resolve(libs);
};
