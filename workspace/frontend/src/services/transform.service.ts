import { xsrfToken } from "./workspace.service";

export interface TransformRequest {
  operation: string;
  ids: string[];
  options?: Record<string, unknown>;
  /** « new » : nouveau document dans `parentId` (racine de Mes documents si absent) ; « version » : nouvelle version de la source. */
  target: "new" | "version";
  parentId?: string;
  /** Nom du résultat, sans extension (le serveur ajoute celle du fichier produit). */
  name?: string;
}

/** Document créé (ou mis à jour) par la transformation. */
export interface TransformResult {
  _id: string;
  name?: string;
  version?: boolean;
}

/**
 * POST /workspace/document/transform. Rejette avec la clé de traduction de l'erreur
 * (« workspace.transform.password.invalid », « workspace.transform.file.too.large »…).
 */
export const transformDocuments = async (req: TransformRequest): Promise<TransformResult> => {
  let res: Response;
  try {
    res = await fetch("/workspace/document/transform", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", Accept: "application/json", "X-XSRF-TOKEN": xsrfToken() },
      body: JSON.stringify(req),
    });
  } catch {
    throw "workspace.transform.unavailable";
  }
  const text = await res.text();
  let body: Record<string, unknown> = {};
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    body = {};
  }
  if (!res.ok) {
    if (typeof body.error === "string") throw body.error;
    if (res.status === 413) throw "workspace.transform.file.too.large";
    if (res.status === 401 || res.status === 403) throw "workspace.transform.forbidden";
    throw "workspace.transform.failed";
  }
  return body as unknown as TransformResult;
};
