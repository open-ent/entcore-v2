import { useQuery } from "@tanstack/react-query";
import { TreeName } from "../lib/types";
import { workspaceService } from "./workspace.service";

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
