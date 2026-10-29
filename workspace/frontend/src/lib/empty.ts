import { TreeName } from "./types";

/**
 * Textes (ceux de l'ancienne interface, dans les traductions communes) d'un emplacement vide.
 * À la racine d'un arbre : un titre et une phrase d'accueil ; dans un sous-dossier : « Ce dossier est vide… ».
 * Ex. racine de « Documents partagés » → « Oups, il n'y a rien sur l'étagère… » + l'explication de cet espace.
 */
export const emptyKeys = (tree: TreeName, atRoot: boolean, searching = false): { title: string; text?: string } => {
  if (searching) return { title: "workspace.react.empty" };
  if (tree === "trash") return { title: atRoot ? "empty.trash2" : "empty.trash.subfolder" };
  if (!atRoot) return { title: "empty.workspace.subfolder.title" };
  if (tree === "owner") return { title: "empty.workspace.title", text: "empty.workspace" };
  if (tree === "shared") return { title: "empty.workspace.shared.title", text: "empty.workspace.shared" };
  if (tree === "protected") return { title: "empty.workspace.apps.title", text: "empty.workspace.apps" };
  return { title: "workspace.react.empty" };
};
