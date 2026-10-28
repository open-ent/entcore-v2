import { TreeName, WorkspaceElement } from "./types";

/**
 * Recherche sans index texte (`disableFullTextSearch`) : on filtre côté navigateur les éléments déjà chargés,
 * dont le nom ou le propriétaire commence par le critère (sans tenir compte de la casse).
 * Ex. critère « te » → « Texte.docx » mais pas « Mon texte.docx ».
 */
export const clientFilter = (elements: WorkspaceElement[], criteria: string): WorkspaceElement[] => {
  const c = criteria.trim().toLowerCase();
  if (!c) return elements;
  return elements.filter((e) => (e.name ?? "").toLowerCase().startsWith(c) || (e.ownerName ?? "").toLowerCase().startsWith(c));
};

/**
 * Adresse de la recherche serveur : dans tout l'espace (`filter=all`), dans un dossier (`ancestorId`) ou à la racine d'un arbre.
 * Ex. ({ criteria: "devoir", everywhere: false, tree: "owner", folderId: "f1" }) → filter=all&…&search=devoir&ancestorId=f1
 */
export const searchQuery = (o: { criteria: string; everywhere: boolean; tree: TreeName; folderId?: string }): string => {
  const sp = new URLSearchParams();
  sp.set("filter", o.everywhere || o.folderId ? "all" : o.tree);
  sp.set("hierarchical", "true");
  sp.set("search", o.criteria.trim());
  sp.set("includeall", "true");
  if (!o.everywhere && o.folderId) sp.set("ancestorId", o.folderId);
  return sp.toString();
};

/**
 * Où ouvrir un résultat de recherche : son dossier parent, sinon l'arbre qui le contient (partagé, corbeille, applis, mes documents).
 * Ex. un document partagé à la racine → "/shared".
 */
export const locationOf = (el: WorkspaceElement & { deleted?: boolean }): string => {
  if (el.eParent) return `/${el.isShared ? "shared" : "owner"}/${el.eParent}`;
  if (el.deleted) return "/trash";
  if (el.isShared) return "/shared";
  if (el.protected) return "/protected";
  return "/owner";
};
