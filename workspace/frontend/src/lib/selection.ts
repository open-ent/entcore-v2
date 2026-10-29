/**
 * Éléments entre le dernier cliqué et celui-ci, bornes comprises (Maj + clic). Sans point de départ connu (ou introuvable),
 * seul l'élément cliqué.
 * Ex. ids [a,b,c,d], départ b, cible d → [b,c,d] ; départ d, cible b → [b,c,d].
 */
export const rangeIds = (ids: string[], anchor: string | undefined, target: string): string[] => {
  const to = ids.indexOf(target);
  const from = anchor ? ids.indexOf(anchor) : -1;
  if (to < 0) return [];
  if (from < 0) return [target];
  return ids.slice(Math.min(from, to), Math.max(from, to) + 1);
};

/** Frappe destinée à un champ de saisie : les raccourcis de la liste ne doivent pas la détourner. */
export const isTypingTarget = (target: EventTarget | null): boolean => {
  const el = target as HTMLElement | null;
  if (!el || typeof el.tagName !== "string") return false;
  return el.isContentEditable === true || ["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName);
};

export type ShortcutAction = "select-all" | "copy" | "move" | null;

/** Raccourci reconnu : Ctrl (ou Cmd) + A = tout sélectionner, + C = copier, + X = déplacer. */
export const shortcutOf = (ev: { key: string; ctrlKey: boolean; metaKey: boolean; altKey?: boolean }): ShortcutAction => {
  if (!(ev.ctrlKey || ev.metaKey) || ev.altKey) return null;
  const k = ev.key.toLowerCase();
  return k === "a" ? "select-all" : k === "c" ? "copy" : k === "x" ? "move" : null;
};
