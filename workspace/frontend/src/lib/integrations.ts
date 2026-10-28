import { WorkspaceElement } from "./types";

export const LOOL_OPEN = "fr.openent.lool.controller.LoolController|open";
export const LOOL_CREATE = "fr.openent.lool.controller.LoolController|createDocumentFromTemplate";

export interface LoolContext {
  provider: string;
  capabilities: Array<{ "content-type": string; extension?: string }>;
}

/** Formats de dessin et d'image que LibreOffice Online ne propose pas d'éditer. */
const DRAW_EXTENSIONS = ["bmp", "cdr", "cmx", "dxf", "emf", "eps", "fodg", "gif", "jpe", "jpeg", "jpg", "met", "odg", "otg", "pbm", "pcd", "pcx", "pdf", "pgm", "png", "ppm", "psd", "pub", "ras", "sda", "sgf", "sgv", "svg", "std", "svm", "sxd", "tga", "tif", "tiff", "vsd", "vdx", "vsdm", "vsdx", "vst", "wmf", "xbm", "xpm"];

export const extensionOf = (name: string): string => /\.([A-Za-z0-9]{1,8})$/.exec(name)?.[1]?.toLowerCase() ?? "";

/**
 * Le fichier peut-il s'ouvrir dans l'éditeur en ligne ? Mêmes règles que l'ancienne interface :
 *  - LibreOffice Online : hors formats de dessin, et son type figure parmi les capacités du serveur ;
 *  - OnlyOffice : son type figure parmi les capacités.
 */
export const canOpenInLool = (ctx: LoolContext | undefined, el: Pick<WorkspaceElement, "name" | "metadata">): boolean => {
  if (!ctx) return false;
  const type = el.metadata?.["content-type"];
  if (!type) return false;
  const supported = ctx.capabilities.some((c) => c["content-type"] === type);
  if (ctx.provider === "LibreOfficeOnline") return !DRAW_EXTENSIONS.includes(extensionOf(el.name)) && supported;
  return supported;
};

/** Projets Scratch 1, 2 et 3 : extension .sb, .sb2 ou .sb3 et type « octet-stream ». */
export const canOpenInScratch = (el: Pick<WorkspaceElement, "name" | "metadata">): boolean =>
  ["sb", "sb2", "sb3"].includes(extensionOf(el.name)) && el.metadata?.["content-type"] === "application/octet-stream";

export const canOpenInGeogebra = (el: Pick<WorkspaceElement, "name">): boolean => extensionOf(el.name) === "ggb";

export const loolUrl = (id: string) => `/lool/documents/${id}/open`;
export const scratchUrl = (id: string) => `/scratch/open?ent_id=${id}`;
/** Ex. "figure.ggb" → /geogebra#/id?fileName=figure.ggb (l'extension n'est ajoutée qu'une fois). */
export const geogebraUrl = (el: Pick<WorkspaceElement, "_id" | "name">) =>
  `/geogebra#/${el._id}?fileName=${encodeURIComponent(el.name.replace(/\.ggb$/i, ""))}.ggb`;

/** Fenêtre de création d'un document bureautique (iframe du connecteur), dans le dossier courant. */
export const loolCreateUrl = (folderId?: string) => (folderId ? `/lool/modal/create?folderId=${encodeURIComponent(folderId)}` : "/lool/modal/create");

export type LoolMessage = { id: string };

/**
 * Réaction aux messages que la fenêtre de création envoie à la page :
 *  - getFolder : répondre avec le dossier courant ; resync : recharger la liste ; close-modal : fermer et recharger ;
 *  - modal-ready : la fenêtre est affichée. Tout autre message est ignoré.
 */
export const loolReaction = (data: unknown): "getFolder" | "resync" | "close" | "ready" | null => {
  const id = (data as LoolMessage | null)?.id;
  switch (id) {
    case "lool@getFolder":
      return "getFolder";
    case "lool@resync":
      return "resync";
    case "lool@close-modal":
      return "close";
    case "lool@modal-ready":
      return "ready";
    default:
      return null;
  }
};

export const parseMessage = (raw: unknown): unknown => {
  if (typeof raw !== "string") return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
};
