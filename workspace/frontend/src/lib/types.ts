/** Arbres racines de l'espace documentaire (filtre `filter` de l'API). */
export type TreeName = "owner" | "shared" | "protected" | "public" | "trash";
export const TREES: TreeName[] = ["owner", "shared", "protected", "public", "trash"];

export interface ElementMetadata {
  "content-type"?: string;
  filename?: string;
  size?: number;
  role?: string;
}

export interface WorkspaceComment {
  id: string;
  author: string;
  authorName?: string;
  comment: string;
  posted?: string;
}

export interface Revision {
  _id: string;
  documentId: string;
  name: string;
  userId: string;
  userName?: string;
  date?: { $date?: string };
  metadata?: ElementMetadata;
}

/** Entrée de partage : un utilisateur (`userId`) ou un groupe (`groupId`) et ses droits, clés `…WorkspaceController|action` → true. */
export interface ShareEntry {
  userId?: string;
  groupId?: string;
  [right: string]: string | boolean | undefined;
}

/** Document ou dossier tel que le renvoient `GET /workspace/documents` et `GET /workspace/folders/list`. */
export interface WorkspaceElement {
  _id: string;
  name: string;
  eType: "file" | "folder";
  eParent: string | null;
  owner: string;
  ownerName?: string;
  created?: string;
  modified?: string;
  metadata?: ElementMetadata;
  shared?: ShareEntry[];
  inheritedShares?: ShareEntry[];
  portalPublication?: { publishedAt?: string };
  comments?: WorkspaceComment[];
  isShared?: boolean;
  protected?: boolean;
  trasher?: string;
  ancestors?: string[];
}

export interface Quota {
  quota: number;
  storage: number;
}

/** « carousel » : diaporama des images du dossier, non mémorisé dans la préférence (comme l'ancienne interface). */
export type ViewMode = "icons" | "list" | "carousel";

/** Clé de traduction (déjà utilisée par l'ancienne interface) du nom d'un arbre racine : « Documents personnels », « Corbeille »… */
export const TREE_LABEL_KEY: Record<TreeName, string> = {
  owner: "documents",
  shared: "shared",
  protected: "appDocuments",
  public: "workspace.react.tree.public",
  trash: "trash",
};

/**
 * Préférence d'interface de l'usager (clé `workspaceUi`). Ex. `{ ui: 'react', returnShown: 2 }`.
 * `ui` absent = la plateforme tranche et le bandeau de retour d'expérience peut s'afficher.
 */
export interface UiPreference {
  ui?: "react" | "angular";
  /** « Ne plus afficher » sur le bandeau de retour. */
  returnDismissed?: boolean;
  returnShown?: number;
  /** Réponse libre à « Qu'est-ce qui vous manque ? », au moment du retour en arrière. */
  feedback?: string;
  feedbackAt?: string;
}
