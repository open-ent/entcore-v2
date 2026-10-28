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

export type ViewMode = "icons" | "list";
