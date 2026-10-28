import { odeServices } from "@open-ent/client";
import { Quota, TreeName, ViewMode, WorkspaceElement } from "../lib/types";

const query = (params: Record<string, string | boolean | undefined>): string => {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined) sp.set(k, String(v));
  return sp.toString();
};

export const workspaceService = {
  /**
   * Contenu d'un emplacement : dossiers ET documents (`includeall`) de l'arbre `tree`, sous `parentId`
   * (absent = racine de l'arbre). Même appel que l'interface AngularJS.
   */
  listContent: (tree: TreeName, parentId?: string) =>
    odeServices
      .http()
      .get<WorkspaceElement[]>(
        `/workspace/documents?${query({ filter: tree, parentId, hierarchical: false, includeall: true })}`,
      ),

  /** Tous les dossiers de l'usager (arborescence de gauche et fil d'Ariane). */
  listFolders: () => odeServices.http().get<WorkspaceElement[]>("/workspace/folders/list?filter=all"),

  getQuota: (userId: string) => odeServices.http().get<Quota>(`/workspace/quota/user/${userId}`),

  /** Préférence d'affichage (« icons » ou « list »), rangée dans la préférence « workspace » de l'usager. */
  async getViewMode(): Promise<ViewMode> {
    const res = await odeServices.http().get<{ preference?: string }>("/userbook/preference/workspace");
    try {
      return JSON.parse(res.preference ?? "{}").view === "list" ? "list" : "icons";
    } catch {
      return "icons";
    }
  },
};
