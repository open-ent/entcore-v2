import { WorkspaceElement } from "./types";

/**
 * Transformations de documents proposées dans le menu « Transformer ». Faites par le serveur
 * (POST /workspace/document/transform, service de documents) ; le résultat est un nouveau
 * document, ou une nouvelle version pour les opérations qui gardent le format PDF.
 */
export type TransformId =
  | "toPdf"
  | "convert"
  | "mergePdf"
  | "pdfPages"
  | "protectPdf"
  | "unlockPdf"
  | "flattenPdf"
  | "rasterizePdf"
  | "compressPdf"
  | "pdfToImages"
  | "imagesToPdf";

export const TRANSFORM_IDS: TransformId[] = [
  "toPdf",
  "convert",
  "imagesToPdf",
  "mergePdf",
  "pdfPages",
  "protectPdf",
  "unlockPdf",
  "flattenPdf",
  "rasterizePdf",
  "compressPdf",
  "pdfToImages",
];

/** Opération du service pour chaque entrée du menu. */
export const OPERATION: Record<TransformId, string> = {
  toPdf: "convert",
  convert: "convert",
  mergePdf: "merge",
  pdfPages: "pages",
  protectPdf: "encrypt",
  unlockPdf: "decrypt",
  flattenPdf: "flatten",
  rasterizePdf: "rasterize",
  compressPdf: "compress",
  pdfToImages: "pdf-to-images",
  imagesToPdf: "images-to-pdf",
};

/** Opérations dont le résultat peut remplacer le document (nouvelle version) : même format, un seul fichier. */
export const VERSIONABLE: TransformId[] = ["pdfPages", "protectPdf", "unlockPdf", "flattenPdf", "rasterizePdf", "compressPdf"];

const FAMILIES: Record<string, string[]> = {
  text: ["doc", "docx", "odt", "rtf", "txt", "html", "htm"],
  sheet: ["xls", "xlsx", "ods", "csv"],
  slides: ["ppt", "pptx", "odp"],
  pdf: ["pdf"],
};
// mêmes cibles que le service (helm/node-pdf-generator/helpers/pdfTools.js, TARGETS)
const TARGETS: Record<string, string[]> = {
  text: ["pdf", "docx", "odt"],
  sheet: ["pdf", "xlsx", "ods", "csv"],
  slides: ["pdf", "pptx", "odp"],
  pdf: ["docx", "odt"],
};
const IMAGES = ["jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "webp"];

/** Extension d'un document : celle du fichier téléversé, sinon celle du nom affiché. */
export const extensionOf = (e: Pick<WorkspaceElement, "name" | "metadata">): string => {
  const filename = String(e.metadata?.filename ?? e.name ?? "");
  const dot = filename.lastIndexOf(".");
  return dot < 0 ? "" : filename.slice(dot + 1).toLowerCase();
};

/** Nom sans extension. */
export const baseName = (e: Pick<WorkspaceElement, "name">): string => {
  const name = e.name ?? "";
  const dot = name.lastIndexOf(".");
  return dot > 0 && name.length - dot <= 6 ? name.slice(0, dot) : name;
};

/** Formats vers lesquels un document peut être converti (le PDF y figure pour les documents Office). */
export const conversionTargets = (ext: string): string[] => {
  const family = Object.keys(FAMILIES).find((k) => FAMILIES[k].includes(ext));
  return family ? TARGETS[family].filter((t) => t !== ext) : [];
};

/** Entrées du menu « Transformer » pour une sélection (fichiers seulement). */
export const availableTransforms = (selection: WorkspaceElement[]): TransformId[] => {
  if (!selection.length || selection.some((e) => e.eType !== "file")) return [];
  const exts = selection.map(extensionOf);
  const result: TransformId[] = [];
  if (exts.every((x) => IMAGES.includes(x))) result.push("imagesToPdf");
  if (exts.length >= 2 && exts.every((x) => x === "pdf")) result.push("mergePdf");
  if (exts.length !== 1) return result;
  const ext = exts[0];
  const targets = conversionTargets(ext);
  if (targets.includes("pdf")) result.push("toPdf");
  if (targets.some((t) => t !== "pdf")) result.push("convert");
  if (ext === "pdf")
    result.push("pdfPages", "protectPdf", "unlockPdf", "flattenPdf", "rasterizePdf", "compressPdf", "pdfToImages");
  return result;
};

/** Nom proposé pour le résultat (sans extension : le serveur ajoute celle du fichier produit). */
export const suggestedName = (
  id: TransformId,
  selection: WorkspaceElement[],
  t: (key: string, opts?: Record<string, unknown>) => string,
): string => {
  const first = baseName(selection[0]);
  switch (id) {
    case "mergePdf":
      return selection.length === 2
        ? t("workspace.transform.name.merge2", { a: first, b: baseName(selection[1]) })
        : t("workspace.transform.name.mergeN", { a: first, n: selection.length - 1 });
    case "imagesToPdf":
      return selection.length === 1 ? first : t("workspace.transform.name.images", { a: first });
    case "protectPdf":
      return t("workspace.transform.name.protected", { a: first });
    case "unlockPdf":
      return t("workspace.transform.name.unlocked", { a: first });
    case "flattenPdf":
      return t("workspace.transform.name.flattened", { a: first });
    case "rasterizePdf":
      return t("workspace.transform.name.rasterized", { a: first });
    case "compressPdf":
      return t("workspace.transform.name.compressed", { a: first });
    case "pdfPages":
      return t("workspace.transform.name.pages", { a: first });
    default:
      return first;
  }
};

/**
 * Sélection de pages saisie par l'usager (« 1-3, 7, 10-fin ») vers la syntaxe du service
 * (« 1-3,7,10-z ») ; null si la saisie est invalide.
 */
export const pagesSelection = (input: string): string | null => {
  const cleaned = input.toLowerCase().replace(/\s+/g, "").replace(/(fin|end|last|dernière|derniere)/g, "z");
  if (!cleaned) return null;
  return /^(\d+|z)(-(\d+|z))?(,(\d+|z)(-(\d+|z))?)*$/.test(cleaned) ? cleaned : null;
};

/** Robustesse d'un mot de passe : 0 (faible) à 3 (solide). */
export const passwordStrength = (pwd: string): 0 | 1 | 2 | 3 => {
  if (pwd.length < 8) return 0;
  const classes = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((r) => r.test(pwd)).length;
  if (pwd.length >= 14 && classes >= 3) return 3;
  if (pwd.length >= 10 && classes >= 2) return 2;
  return 1;
};
