import { forbiddenTargets } from "./actions";
import { WorkspaceElement } from "./types";

/** Type de données du glisser-déposer interne (éléments de l'espace documentaire déplacés vers un dossier). */
export const DRAG_TYPE = "application/x-workspace-elements";

/**
 * Éléments emportés par un glisser : toute la sélection si l'élément saisi en fait partie, sinon lui seul.
 * Ex. sélection {a, b}, on saisit b → [a, b] ; on saisit c (non coché) → [c].
 */
export const draggedElements = (grabbed: WorkspaceElement, selection: WorkspaceElement[]): WorkspaceElement[] =>
  selection.some((e) => e._id === grabbed._id) ? selection : [grabbed];

/**
 * Peut-on déposer ces éléments sur ce dossier (`null` = racine de « Mes documents`) ?
 * Non si c'est l'un d'eux ou l'un de leurs sous-dossiers, ou si tous s'y trouvent déjà (déplacement sans effet).
 */
export const canDropOn = (targetId: string | null, dragged: WorkspaceElement[], folders: WorkspaceElement[]): boolean => {
  if (dragged.length === 0) return false;
  if (targetId !== null && forbiddenTargets(folders, dragged).has(targetId)) return false;
  return !dragged.every((e) => (e.eParent ?? null) === targetId);
};

export const encodeDrag = (els: WorkspaceElement[]): string => JSON.stringify(els.map((e) => e._id));

export const decodeDrag = (raw: string | undefined): string[] => {
  try {
    const ids = JSON.parse(raw ?? "[]");
    return Array.isArray(ids) ? ids.filter((i): i is string => typeof i === "string") : [];
  } catch {
    return [];
  }
};

/** Un glisser est interne s'il porte nos éléments (et non des fichiers venus du poste). */
export const isInternalDrag = (types: readonly string[]): boolean => types.includes(DRAG_TYPE);
