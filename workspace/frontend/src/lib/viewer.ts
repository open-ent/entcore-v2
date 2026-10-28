import { WorkspaceElement } from "./types";

/** Façon d'afficher un fichier dans la visionneuse. */
export type PreviewKind = "image" | "audio" | "video" | "pdf" | "office" | "table" | "text" | "html" | "none";

const ext = (name: string) => /\.([A-Za-z0-9]{1,8})$/.exec(name)?.[1]?.toLowerCase() ?? "";

/**
 * Mode d'affichage d'un fichier d'après son type MIME, à défaut son extension.
 *  - office : traitement de texte et présentation, affichés par l'aperçu PDF du serveur ;
 *  - table : tableur et CSV, affichés en tableau ; none : téléchargement seul (archives, formats inconnus).
 * Ex. ("application/octet-stream", "devoir.docx") → "office".
 */
export const previewKind = (contentType: string | undefined, name: string): PreviewKind => {
  const t = (contentType ?? "").toLowerCase();
  const e = ext(name);
  if (t.startsWith("image/")) return "image";
  if (t.startsWith("audio/")) return "audio";
  if (t.startsWith("video/")) return "video";
  if (t === "application/pdf" || e === "pdf") return "pdf";
  if (/spreadsheet|ms-excel|csv/.test(t) || ["xls", "xlsx", "ods", "csv"].includes(e)) return "table";
  if (/wordprocessing|msword|presentation|powerpoint|opendocument\.text|rtf/.test(t) || ["doc", "docx", "odt", "rtf", "ppt", "pptx", "odp"].includes(e)) return "office";
  if (t === "text/html" || e === "html" || e === "htm") return "html";
  if (t.startsWith("text/") || e === "txt") return "text";
  return "none";
};

/** Adresse du contenu à afficher : le fichier lui-même, ou son aperçu converti pour les formats bureautiques et tableurs. */
export const previewUrlFor = (el: Pick<WorkspaceElement, "_id" | "name" | "metadata">): string => {
  const kind = previewKind(el.metadata?.["content-type"], el.name);
  const isCsv = /\.csv$/i.test(el.name) || el.metadata?.["content-type"] === "text/csv";
  return kind === "office" || (kind === "table" && !isCsv) ? `/workspace/document/preview/${el._id}` : `/workspace/document/${el._id}`;
};

/** Séparateur le plus fréquent sur la première ligne parmi « ; », « , » et tabulation. */
export const detectDelimiter = (text: string): string => {
  const first = text.split(/\r?\n/, 1)[0] ?? "";
  const counts = [";", ",", "\t"].map((d) => [d, first.split(d).length - 1] as const);
  return counts.sort((a, b) => b[1] - a[1])[0][1] > 0 ? counts.sort((a, b) => b[1] - a[1])[0][0] : ",";
};

/**
 * Lecture d'un CSV (guillemets, séparateur automatique, retours à la ligne dans un champ guillemeté).
 * Ex. 'a;"b;c"\n1;2' → [["a","b;c"],["1","2"]].
 */
export const parseCsv = (text: string): string[][] => {
  const delimiter = detectDelimiter(text);
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === delimiter) {
      row.push(field);
      field = "";
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else field += c;
  }
  if (field !== "" || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows.filter((r) => r.some((cell) => cell !== ""));
};
