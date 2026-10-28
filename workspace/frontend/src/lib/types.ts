/** Arbres racines de l'espace documentaire (filtre `filter` de l'API). */
export type TreeName = "owner" | "shared" | "protected" | "public" | "trash";
export const TREES: TreeName[] = ["owner", "shared", "protected", "public", "trash"];

export interface ElementMetadata {
  "content-type"?: string;
  filename?: string;
  size?: number;
  role?: string;
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
  shared?: unknown[];
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
