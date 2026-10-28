import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { fileKind } from "../lib/elements";
import { uploadUrl } from "../lib/upload";
import { uploadFile } from "./workspace.service";

export interface UploadItem {
  id: number;
  name: string;
  status: "loading" | "done" | "error";
  percent: number;
  /** Clé de traduction du message d'erreur */
  error?: string;
}

let counter = 0;

/** Envois en cours : un élément par fichier, avec progression ; la liste et la jauge sont rechargées à chaque fin d'envoi. */
export const useUploads = (parentId?: string) => {
  const [items, setItems] = useState<UploadItem[]>([]);
  const qc = useQueryClient();

  const patch = (id: number, p: Partial<UploadItem>) => setItems((list) => list.map((i) => (i.id === id ? { ...i, ...p } : i)));

  const send = useCallback(
    async (files: File[], zip = false) => {
      await Promise.all(
        files.map(async (file) => {
          const id = ++counter;
          setItems((list) => [...list, { id, name: file.name, status: "loading", percent: 0 }]);
          try {
            const url = uploadUrl({ zip, parentId, image: fileKind(file.type) === "image" });
            await uploadFile(file, url, (percent) => patch(id, { percent }));
            patch(id, { status: "done", percent: 100 });
          } catch (key) {
            patch(id, { status: "error", error: String(key) });
          }
          qc.invalidateQueries({ queryKey: ["workspace", "content"] });
          qc.invalidateQueries({ queryKey: ["workspace", "quota"] });
        }),
      );
    },
    [parentId, qc],
  );

  const clear = () => setItems((list) => list.filter((i) => i.status === "loading"));
  return { items, send, clear };
};
