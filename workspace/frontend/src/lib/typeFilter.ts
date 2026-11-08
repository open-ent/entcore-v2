import { fileKind, FileKind } from "./elements";
import { WorkspaceElement } from "./types";

/**
 * Familles proposées en filtre au-dessus de la liste. Plus large que `FileKind` : on regroupe ce que l'usager range
 * ensemble (un .txt, un .pdf et un .docx sont tous des « documents ») et on garde à part ce qu'il cherche séparément.
 */
export type TypeFilterKey = "all" | "folders" | "images" | "videos" | "audio" | "documents" | "sheets" | "slides" | "archives" | "other";

/** Famille d'un `FileKind`. Ex. "text" → "documents" ; "sheet" → "sheets". */
const GROUP_OF: Record<FileKind, Exclude<TypeFilterKey, "all" | "folders">> = {
  image: "images",
  video: "videos",
  audio: "audio",
  pdf: "documents",
  doc: "documents",
  text: "documents",
  sheet: "sheets",
  slides: "slides",
  archive: "archives",
  other: "other",
};

/** Ordre d'affichage des puces, « Tout » en tête. */
export const FILTER_ORDER: TypeFilterKey[] = ["all", "folders", "images", "videos", "audio", "documents", "sheets", "slides", "archives", "other"];

export const FILTER_LABEL_KEY: Record<TypeFilterKey, string> = {
  all: "workspace.react.filter.all",
  folders: "workspace.react.filter.folders",
  images: "workspace.react.filter.images",
  videos: "workspace.react.filter.videos",
  audio: "workspace.react.filter.audio",
  documents: "workspace.react.filter.documents",
  sheets: "workspace.react.filter.sheets",
  slides: "workspace.react.filter.slides",
  archives: "workspace.react.filter.archives",
  other: "workspace.react.filter.other",
};

/** Famille d'un élément : un dossier en forme une à lui seul. */
export const filterKeyOf = (e: WorkspaceElement): TypeFilterKey =>
  e.eType === "folder" ? "folders" : GROUP_OF[fileKind(e.metadata?.["content-type"])];

/**
 * Puces à afficher pour une liste : « Tout » avec le total, puis seulement les familles réellement présentes, dans
 * l'ordre habituel. Une liste vide ne donne aucune puce (il n'y a rien à filtrer).
 * Ex. [3 dossiers, 5 vidéos, 1 texte] → [all:9, folders:3, videos:5, documents:1].
 */
export const typeFilterCounts = (elements: WorkspaceElement[]): Array<{ key: TypeFilterKey; count: number }> => {
  if (elements.length === 0) return [];
  const counts = new Map<TypeFilterKey, number>();
  for (const e of elements) {
    const key = filterKeyOf(e);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return FILTER_ORDER.filter((k) => k === "all" || counts.has(k)).map((key) => ({
    key,
    count: key === "all" ? elements.length : (counts.get(key) as number),
  }));
};

/** Applique la famille choisie. « Tout » (ou une famille absente de la liste) ne retire rien. */
export const applyTypeFilter = (elements: WorkspaceElement[], key: TypeFilterKey): WorkspaceElement[] =>
  key === "all" ? elements : elements.filter((e) => filterKeyOf(e) === key);
