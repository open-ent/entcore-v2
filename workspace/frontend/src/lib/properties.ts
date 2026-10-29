import { WorkspaceElement } from "./types";

const PREFIX = "org-entcore-workspace-controllers-WorkspaceController|";
/** Droits qui font d'une personne ou d'un groupe un gestionnaire du dossier (peut renommer). */
export const RENAME_RIGHTS = [`${PREFIX}renameDocument`, `${PREFIX}renameFolder`];

/** Longueur maximale d'un nom, et caractères interdits (ils sont refusés à la frappe et retirés à l'enregistrement). */
export const MAX_NAME = 80;
export const FORBIDDEN_CHARS = /[/\\<>|]/;

export const isForbiddenChar = (key: string): boolean => key.length === 1 && FORBIDDEN_CHARS.test(key);
/** Nom prêt à être enregistré : espaces de bord et caractères interdits retirés. Ex. « a/b<c> » → « abc ». */
export const cleanName = (name: string): string => name.trim().replace(/[/\\<>|]/g, "");

/** Dossier parent tel que le renvoie `GET /workspace/document/parent/:id` : droits hérités et noms des personnes et groupes visibles. */
export interface ParentInfo extends WorkspaceElement {
  visibleUsers?: Array<{ id: string; username?: string }>;
  visibleGroups?: Array<{ id: string; name?: string }>;
}

export interface Manager {
  id: string;
  name: string;
  uri: string;
}

/**
 * Gestionnaires du dossier parent : son propriétaire, puis chaque personne ou groupe ayant un droit de renommer.
 * Ex. propriétaire u1 + partage { userId: u2, renameFolder } + partage { groupId: g1, renameFolder } → [u1, u2, g1].
 */
export const managersOf = (parent: ParentInfo | undefined): Manager[] => {
  if (!parent) return [];
  const ownerId = typeof parent.owner === "string" ? parent.owner : (parent.owner as unknown as { userId?: string })?.userId ?? "";
  const managers: Manager[] = [{ id: ownerId, name: parent.ownerName ?? "", uri: `/userbook/annuaire#/${ownerId}` }];
  for (const share of parent.inheritedShares ?? []) {
    if (!RENAME_RIGHTS.some((r) => share[r])) continue;
    if (share.userId) {
      if (share.userId === ownerId) continue;
      const user = parent.visibleUsers?.find((u) => u.id === share.userId);
      managers.push({ id: share.userId, name: user?.username ?? "", uri: `/userbook/annuaire#/${share.userId}` });
    } else if (share.groupId) {
      const group = parent.visibleGroups?.find((g) => g.id === share.groupId);
      managers.push({ id: share.groupId, name: group?.name ?? "", uri: `/userbook/annuaire#/group-view/${share.groupId}` });
    }
  }
  return managers;
};
