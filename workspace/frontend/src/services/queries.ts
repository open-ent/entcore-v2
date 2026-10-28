import { useQuery } from "@tanstack/react-query";
import { TreeName } from "../lib/types";
import { fetchText, getElement, workspaceService } from "./workspace.service";

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

export const useViewModePreference = () =>
  useQuery({ queryKey: ["workspace", "view-mode"], queryFn: workspaceService.getViewMode });

export const useElement = (id?: string) =>
  useQuery({ queryKey: ["workspace", "element", id], queryFn: () => getElement(id as string), enabled: !!id });

export const useTextContent = (url?: string) =>
  useQuery({ queryKey: ["workspace", "text", url], queryFn: () => fetchText(url as string), enabled: !!url });
