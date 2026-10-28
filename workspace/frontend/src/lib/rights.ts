import { ShareEntry, WorkspaceElement } from "./types";

const PREFIX = "org-entcore-workspace-controllers-WorkspaceController|";

export interface Rights {
  read: boolean;
  contrib: boolean;
  manager: boolean;
  comment: boolean;
}

/**
 * Droits de l'usager sur un élément : propriétaire = tous ; sinon ceux des entrées de partage qui le visent
 * (lui-même ou l'un de ses groupes), partage direct ou hérité d'un dossier parent.
 *  - lecture : `getDocument` ; contribution : `updateDocument` (donne aussi la lecture) ; gestion : `shareJson` (donne tout) ;
 *    commentaire : `commentDocument`.
 * Ex. entrée { groupId: "g1", "…|updateDocument": true } et l'usager dans g1 → { read, contrib, !manager }.
 */
export const rightsOn = (element: WorkspaceElement, userId: string | undefined, groupIds: string[] = []): Rights => {
  if (userId && element.owner === userId) return { read: true, contrib: true, manager: true, comment: true };
  const mine = (e: ShareEntry) => (e.userId !== undefined && e.userId === userId) || (e.groupId !== undefined && groupIds.includes(e.groupId));
  const entries = [...(element.shared ?? []), ...(element.inheritedShares ?? [])].filter(mine);
  const has = (action: string) => entries.some((e) => e[PREFIX + action] === true);
  const manager = has("shareJson");
  const contrib = manager || has("updateDocument");
  const read = contrib || has("getDocument");
  return { read, contrib, manager, comment: manager || has("commentDocument") };
};
