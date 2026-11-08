import { FileKind } from "../lib/elements";

/**
 * Couleur du type, prise dans les utilitaires du thème (jamais une couleur écrite ici) : les dossiers et les documents
 * bureautiques se distinguent au premier coup d'œil, et un skin d'établissement qui change la palette les suit.
 */
export const KIND_COLOR: Record<FileKind | "folder", string> = {
  folder: "text-warning",
  image: "text-success",
  pdf: "text-danger",
  doc: "text-secondary",
  sheet: "text-success",
  slides: "text-warning",
  audio: "text-info",
  video: "text-secondary",
  text: "text-gray-700",
  archive: "text-gray-700",
  other: "text-gray-700",
};

/** Clé de traduction du libellé d'un type, affiché sous le nom d'une carte (« Vidéo · 46,9 Mo »). */
export const KIND_LABEL_KEY: Record<FileKind | "folder", string> = {
  folder: "workspace.react.type.folder",
  image: "workspace.react.type.image",
  pdf: "workspace.react.type.pdf",
  doc: "workspace.react.type.doc",
  sheet: "workspace.react.type.sheet",
  slides: "workspace.react.type.slides",
  audio: "workspace.react.type.audio",
  video: "workspace.react.type.video",
  text: "workspace.react.type.text",
  archive: "workspace.react.type.archive",
  other: "workspace.react.type.other",
};

/** Tracé propre à chaque type, posé sur la feuille d'un document (ou sur la chemise d'un dossier). */
const GLYPH: Record<FileKind | "folder", JSX.Element> = {
  folder: <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" />,
  image: (
    <>
      <circle cx="9.5" cy="10.5" r="1.5" />
      <path d="m5 18 4-4.5 3 3L15.5 12 19 18H5Z" />
    </>
  ),
  pdf: <path d="M8 13h1.6a1.2 1.2 0 0 1 0 2.4H8V13Zm0 2.4V18m3.6-5h1a1.6 1.6 0 0 1 1.6 1.6v1.8A1.6 1.6 0 0 1 12.6 18h-1v-5Zm5 0H19m-2.4 0v5m0-2.6h2" />,
  doc: <path d="M8 11h8M8 14h8M8 17h5" />,
  sheet: <path d="M7 11h10M7 14.5h10M7 18h10M11 11v7M14 11v7" />,
  slides: (
    <>
      <rect x="7" y="11" width="10" height="6.5" rx="1" />
      <path d="M12 17.5V20" />
    </>
  ),
  audio: <path d="M15 9v7.2a2 2 0 1 1-1.4-1.9V11l-4 1v5.2a2 2 0 1 1-1.4-1.9V10.5L15 9Z" />,
  video: <path d="M7 11h6.5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1Zm7.5 2.5 3.5-2v6l-3.5-2v-2Z" />,
  text: <path d="M8 11h8M8 14h8M8 17h4" />,
  archive: <path d="M12 6v2m0 2v2m0 2v2m-1 2h2v2h-2v-2Z" />,
  other: <path d="M15 5v5h5" />,
};

/**
 * Icône d'un dossier ou d'un document. Le socle n'emporte pas de jeu d'icônes par type de fichier : on le dessine ici,
 * en `currentColor`, pour que la couleur vienne d'une classe du thème.
 * Ex. ("video") → la feuille barrée d'une pellicule, en couleur d'alerte.
 */
export const FileIcon = ({ kind, small = false, className }: { kind: FileKind | "folder"; small?: boolean; className?: string }) => (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={1.6}
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
    focusable="false"
    data-file-icon={kind}
    className={`${small ? "ws-file-icon-sm" : "ws-file-icon"} ${KIND_COLOR[kind]}${className ? ` ${className}` : ""}`}
  >
    {kind !== "folder" && <path d="M6 4h8l5 5v11a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z" />}
    {GLYPH[kind]}
  </svg>
);
