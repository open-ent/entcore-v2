import { Rights } from "./rights";
import { Revision, WorkspaceComment } from "./types";

/**
 * Une version se supprime si l'usager gère le document, ou s'il en est l'auteur et peut contribuer — et jamais
 * la dernière : un document garde toujours au moins une version.
 */
export const canDeleteRevision = (revision: Revision, rights: Rights, userId: string | undefined, revisionCount: number): boolean =>
  revisionCount > 1 && (rights.manager || (!!userId && revision.userId === userId && rights.contrib));

/** Un commentaire se supprime par un gestionnaire, ou par son auteur s'il a encore le droit de commenter. */
export const canDeleteComment = (comment: WorkspaceComment, rights: Rights, userId: string | undefined): boolean =>
  rights.manager || (!!userId && comment.author === userId && rights.comment);

/** Versions de la plus récente à la plus ancienne. */
export const newestFirst = (revisions: Revision[]): Revision[] =>
  [...revisions].sort((a, b) => (b.date?.$date ?? "").localeCompare(a.date?.$date ?? ""));
