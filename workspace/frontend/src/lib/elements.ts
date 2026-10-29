import { TREES, TreeName, WorkspaceElement } from "./types";

export type FileKind = "image" | "pdf" | "doc" | "sheet" | "slides" | "audio" | "video" | "text" | "archive" | "other";

/**
 * Catégorie d'un fichier d'après son type MIME (icône et libellé).
 * Ex. "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" → "sheet".
 */
export const fileKind = (contentType?: string): FileKind => {
  const t = (contentType ?? "").toLowerCase();
  if (t.startsWith("image/")) return "image";
  if (t.startsWith("audio/")) return "audio";
  if (t.startsWith("video/")) return "video";
  if (t === "application/pdf") return "pdf";
  if (/spreadsheet|ms-excel|csv/.test(t)) return "sheet";
  if (/presentation|powerpoint/.test(t)) return "slides";
  if (/wordprocessing|msword|opendocument\.text|rtf/.test(t)) return "doc";
  if (t.startsWith("text/")) return "text";
  if (/zip|compressed|tar|rar|7z/.test(t)) return "archive";
  return "other";
};

/** Taille lisible : 1163 → "1,1 Ko". Absente ou négative → "". */
export const formatSize = (bytes?: number): string => {
  if (bytes === undefined || bytes === null || bytes < 0) return "";
  const units = ["o", "Ko", "Mo", "Go"];
  let value = bytes;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i++;
  }
  const text = i === 0 ? String(value) : value.toFixed(1).replace(".", ",");
  return `${text} ${units[i]}`;
};

/**
 * Date de l'API. Le serveur écrit "2026-10-28 17:22.33.755" (un point entre minutes et secondes) :
 * on remplace ce point pour obtenir une date ISO lisible par `Date`. Illisible → undefined.
 */
export const parseDate = (raw?: string): Date | undefined => {
  if (!raw) return undefined;
  const m = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})[.:](\d{2})(?:\.\d+)?$/.exec(raw.trim());
  if (!m) return undefined;
  const d = new Date(`${m[1]}T${m[2]}:${m[3]}`);
  return Number.isNaN(d.getTime()) ? undefined : d;
};

export type SortKey = "name" | "owner" | "modified" | "size";

/**
 * Dossiers d'abord, puis le critère choisi (nom et propriétaire : ordre alphabétique insensible aux accents et à la casse).
 * `desc` absent : sens habituel de chaque critère (nom et propriétaire croissants, date et taille décroissantes) ; sinon le sens demandé.
 */
export const sortElements = (elements: WorkspaceElement[], key: SortKey = "name", desc?: boolean): WorkspaceElement[] => {
  const down = desc ?? (key === "modified" || key === "size");
  const text = (a?: string, b?: string) => (a ?? "").localeCompare(b ?? "", "fr", { sensitivity: "base", numeric: true });
  const ascending = (a: WorkspaceElement, b: WorkspaceElement): number => {
    if (key === "modified") return (parseDate(a.modified)?.getTime() ?? 0) - (parseDate(b.modified)?.getTime() ?? 0);
    if (key === "size") return (a.metadata?.size ?? 0) - (b.metadata?.size ?? 0);
    if (key === "owner") return text(a.ownerName, b.ownerName) || text(a.name, b.name);
    return text(a.name, b.name);
  };
  const cmp = (a: WorkspaceElement, b: WorkspaceElement): number => {
    if (a.eType !== b.eType) return a.eType === "folder" ? -1 : 1;
    return down ? -ascending(a, b) : ascending(a, b);
  };
  return [...elements].sort(cmp);
};

/** Dossiers enfants directs d'un parent (null = racine de l'arbre). */
export const childFolders = (folders: WorkspaceElement[], parentId: string | null): WorkspaceElement[] =>
  // un dossier mis à la corbeille (marqué par `trasher`) disparaît de l'arborescence, même si la liste serveur le renvoie encore
  sortElements(folders.filter((f) => (f.eParent ?? null) === parentId && !f.trasher));

/**
 * Chemin du dossier courant, de la racine vers lui : [Mes dossiers, Cours, 6e].
 * Un dossier introuvable ou une boucle de parenté interrompent proprement le chemin (jamais de boucle infinie).
 */
export const breadcrumb = (folders: WorkspaceElement[], folderId: string | null): WorkspaceElement[] => {
  const byId = new Map(folders.map((f) => [f._id, f]));
  const path: WorkspaceElement[] = [];
  const seen = new Set<string>();
  let cursor = folderId ? byId.get(folderId) : undefined;
  while (cursor && !seen.has(cursor._id)) {
    path.unshift(cursor);
    seen.add(cursor._id);
    cursor = cursor.eParent ? byId.get(cursor.eParent) : undefined;
  }
  return path;
};

export const isTreeName = (value?: string): value is TreeName => !!value && (TREES as string[]).includes(value);
