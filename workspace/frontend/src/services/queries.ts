import { odeServices } from "@open-ent/client";
import { useQuery } from "@tanstack/react-query";
import { LoolContext } from "../lib/integrations";
import { nextcloudService } from "./nextcloud.service";
import { TreeName } from "../lib/types";
import { fetchText, getElement, publishedIds, searchElements, workspaceService } from "./workspace.service";

export const useContent = (tree: TreeName, parentId?: string) =>
  useQuery({
    queryKey: ["workspace", "content", tree, parentId ?? null],
    queryFn: () => workspaceService.listContent(tree, parentId),
  });

export const useFolders = () =>
  useQuery({ queryKey: ["workspace", "folders"], queryFn: workspaceService.listFolders });

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
