import { rightsOn } from "./rights";
import { WorkspaceElement } from "./types";

/** Un élément est « partagé » dès qu'il a des droits de partage, posés sur lui ou hérités de son dossier. */
export const isSharedElement = (e: Pick<WorkspaceElement, "shared" | "inheritedShares">): boolean =>
  (e.shared?.length ?? 0) > 0 || (e.inheritedShares?.length ?? 0) > 0;

/** Sens d'un déplacement : « toshare » (perso → partagé), « toown » (partagé → perso), « fromtoshare » (partagé → partagé), « nope » (perso → perso). */
export type Direction = "toshare" | "toown" | "fromtoshare" | "nope";

/**
 * Sens du transfert, comme l'ancienne interface : un déplacement qui change d'espace de partage demande une confirmation, car
 * des personnes peuvent perdre l'accès.
 * Ex. deux dossiers personnels vers un dossier partagé → « toshare » ; un dossier partagé vers « Mes documents » → « toown ».
 */
export const directionOf = (elements: Array<Pick<WorkspaceElement, "shared" | "inheritedShares">>, destShared: boolean): Direction => {
  const containsOwned = elements.some((e) => !isSharedElement(e));
  const containsShared = elements.some((e) => isSharedElement(e));
  if (destShared && containsOwned) return "toshare";
  if (!destShared && containsShared) return "toown";
  if (destShared && containsShared) return "fromtoshare";
  return "nope";
};

/** Destinations proposées : dossiers personnels, et dossiers partagés où l'usager peut contribuer. */
export const destinations = (folders: WorkspaceElement[], userId: string | undefined, groupIds: string[]) => {
  const live = folders.filter((f) => !f.trasher);
  return {
    personal: live.filter((f) => f.owner === userId && !isSharedElement(f)),
    shared: live.filter((f) => isSharedElement(f) && rightsOn(f, userId, groupIds).contrib),
  };
};

export type TransferMode = "copy" | "move";

/** Clés de traduction (celles de l'ancienne interface) de chaque étape. */
export const transferKeys = (mode: TransferMode) =>
  mode === "copy"
    ? { title: "workspace.copy.window.title", info: "workspace.copy.window.info", action: "workspace.copy.window.action", processing: "workspace.copy.window.processing", finished: "workspace.copy.window.finished" }
    : { title: "workspace.move.window.title", info: "workspace.move.window.info", action: "workspace.move.window.action", processing: "workspace.move.window.processing", finished: "workspace.move.window.finished" };

/**
 * Avertissement avant un déplacement qui change d'espace de partage : titre et phrases à afficher.
 * `mine` : tous les éléments sont à l'usager (sinon, il en devient gestionnaire et les autres perdent l'accès).
 */
export const impactKeys = (direction: Exclude<Direction, "nope">, mine: boolean): { title: string; messages: string[]; canCopyInstead: boolean } => {
  if (direction === "toshare") return { title: "workspace.move.warnings.title.toshare", messages: ["workspace.move.window.toshare"], canCopyInstead: true };
  if (direction === "fromtoshare") return { title: "workspace.move.warnings.title", messages: ["workspace.move.window.fromtoshare", "workspace.move.window.copyinsteadofmove"], canCopyInstead: true };
  return mine
    ? { title: "workspace.move.warnings.title", messages: ["workspace.move.window.toown", "workspace.move.window.copyinsteadofmove"], canCopyInstead: true }
    : { title: "workspace.move.warnings.title", messages: ["workspace.move.window.fromshare.notmine"], canCopyInstead: true };
};

/** Clé d'erreur renvoyée par le serveur (« { error: 'workspace.contrib.cant' } »), ou celle par défaut. */
export const errorKeyOf = (error: unknown, fallback = "workspace.react.action.failed"): string => {
  const message = (error as { message?: string } | undefined)?.message;
  return message && /^[a-z][\w.-]+\.[\w.-]+$/i.test(message) && !/^\d+$/.test(message) ? message : fallback;
};
