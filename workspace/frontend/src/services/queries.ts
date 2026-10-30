import { odeServices } from "@open-ent/client";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useMemo } from "react";
import { readFlags } from "../lib/flags";
import { mergeFolders } from "../lib/lazyFolders";
import { WorkspaceElement } from "../lib/types";
import { LoolContext } from "../lib/integrations";
import { nextcloudService } from "./nextcloud.service";
import { TreeName } from "../lib/types";
import { fetchText, getElement, publishedIds, searchElements, workspaceService } from "./workspace.service";

export const useContent = (tree: TreeName, parentId?: string) =>
  useQuery({
    queryKey: ["workspace", "content", tree, parentId ?? null],
    queryFn: () => workspaceService.listContent(tree, parentId),
  });

const FOLDERS_KEY = ["workspace", "folders"];

/** Mode « lazy » de la plateforme : l'arbre ne charge que les racines, le reste au dépliage. */
const lazyMode = () => readFlags().lazyMode;

export const useFolders = () =>
  useQuery({ queryKey: FOLDERS_KEY, queryFn: lazyMode() ? workspaceService.listRootFolders : workspaceService.listFolders });

/**
 * Mode « lazy » : chargement des dossiers à la demande. `loadChildren` ajoute les sous-dossiers d'un dossier que l'on déplie ;
 * `ensureFolder` remonte la chaîne des parents d'un dossier ouvert par lien direct (fil d'Ariane).
 * Hors mode lazy tout est déjà chargé : les deux ne font rien et `lazy` est faux.
 */
export const useFolderStore = () => {
  const qc = useQueryClient();
  const lazy = useMemo(lazyMode, []);
  const add = useCallback((incoming: WorkspaceElement[]) => qc.setQueryData<WorkspaceElement[]>(FOLDERS_KEY, (cur) => mergeFolders(cur ?? [], incoming)), [qc]);
  const loadChildren = useCallback(
    async (id: string) => {
      if (!lazy || workspaceService.isLazyLoaded(id)) return;
      add(await workspaceService.listChildFolders(id));
    },
    [lazy, add],
  );
  const ensureFolder = useCallback(
    async (id: string) => {
      if (!lazy) return;
      let cursor: string | null | undefined = id;
      const seen = new Set<string>();
      while (cursor && !seen.has(cursor)) {
        seen.add(cursor);
        const known = (qc.getQueryData<WorkspaceElement[]>(FOLDERS_KEY) ?? []).find((f) => f._id === cursor);
        const folder: WorkspaceElement | undefined = known ?? (await workspaceService.getFolder(cursor));
        if (!folder) return;
        if (!known) add([folder]);
        cursor = folder.eParent;
      }
    },
    [lazy, qc, add],
  );
  return { lazy, isLoaded: workspaceService.isLazyLoaded, loadChildren, ensureFolder };
};

export const useQuota = (userId?: string) =>
  useQuery({
    queryKey: ["workspace", "quota", userId],
    queryFn: () => workspaceService.getQuota(userId as string),
    enabled: !!userId,
  });

export const useWorkspacePreference = () => useQuery({ queryKey: ["workspace", "preference"], queryFn: workspaceService.getPreference, staleTime: Infinity });

export const useElement = (id?: string) =>
  useQuery({ queryKey: ["workspace", "element", id], queryFn: () => getElement(id as string), enabled: !!id });

export const useTextContent = (url?: string) =>
  useQuery({ queryKey: ["workspace", "text", url], queryFn: () => fetchText(url as string), enabled: !!url });

export const useSearch = (queryString?: string) =>
  useQuery({ queryKey: ["workspace", "search", queryString], queryFn: () => searchElements(queryString as string), enabled: !!queryString });

/** Capacités du serveur d'édition en ligne (connecteur lool) ; non demandées si l'édition en ligne est désactivée. */
export const useLoolContext = (enabled: boolean) =>
  useQuery({
    queryKey: ["workspace", "lool-context"],
    queryFn: () => odeServices.http().get<LoolContext>("/lool/providers/context"),
    enabled,
    staleTime: 1000 * 60 * 10,
  });

export const useNextcloudList = (userId: string | undefined, path: string, trash: boolean, enabled: boolean) =>
  useQuery({
    queryKey: ["workspace", "nextcloud", userId, trash ? "trash" : path],
    queryFn: () => (trash ? nextcloudService.listTrash(userId as string) : nextcloudService.list(userId as string, path)),
    enabled: enabled && !!userId,
  });

export const useNextcloudUrl = (enabled: boolean) =>
  useQuery({
    queryKey: ["workspace", "nextcloud-url"],
    queryFn: async () => ({ url: await nextcloudService.url(), hidden: await nextcloudService.urlHidden() }),
    enabled,
    staleTime: 1000 * 60 * 10,
  });

export const usePublishedIds = (structureIds: string[], enabled: boolean) =>
  useQuery({ queryKey: ["workspace", "published", structureIds], queryFn: () => publishedIds(structureIds), enabled: enabled && structureIds.length > 0 });

export const useNextcloudQuota = (userId: string | undefined, enabled: boolean) =>
  useQuery({ queryKey: ["workspace", "nextcloud-quota", userId], queryFn: () => nextcloudService.quota(userId as string), enabled: enabled && !!userId });

/** Dossier parent de l'élément dont on ouvre les propriétés (absent pour un élément à la racine). */
export const useParentInfo = (elementId?: string, enabled = true) =>
  useQuery({ queryKey: ["workspace", "parent", elementId], queryFn: () => workspaceService.parentInfo(elementId as string), enabled: !!elementId && enabled, staleTime: 30_000 });
