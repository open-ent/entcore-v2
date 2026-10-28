import { Quota } from "./types";
import { formatSize } from "./elements";

/** Nom de dossier valide : non vide après suppression des espaces, sans « _ » (règle du serveur, cf. workspace.folder.invalid.name). */
export const folderNameError = (raw: string): "empty" | "underscore" | null => {
  const name = raw.trim();
  if (!name) return "empty";
  if (name.includes("_")) return "underscore";
  return null;
};

export const isZip = (file: { name: string; type?: string }): boolean =>
  /\.zip$/i.test(file.name) || file.type === "application/zip" || file.type === "application/x-zip-compressed";

export type RejectReason = "too-large" | "quota";

export interface UploadPlan<F extends { size: number }> {
  accepted: F[];
  rejected: Array<{ file: F; reason: RejectReason }>;
  /** Espace restant en octets, pour le message « Espace insuffisant… Espace restant : X ». */
  remaining: number;
}

/**
 * Sépare les fichiers acceptables des refusés avant tout envoi.
 * Un fichier plus gros que la limite unitaire est refusé ; ensuite, on accepte dans l'ordre tant que
 * le cumul tient dans l'espace restant (quota − déjà utilisé).
 * Ex. reste 10 Mo, [4 Mo, 8 Mo, 5 Mo] → accepte 4 Mo et 5 Mo, refuse 8 Mo (quota).
 */
export const planUpload = <F extends { size: number }>(files: F[], quota: Quota | undefined, maxFileSize: number): UploadPlan<F> => {
  const remaining = quota ? Math.max(0, quota.quota - quota.storage) : Number.POSITIVE_INFINITY;
  const accepted: F[] = [];
  const rejected: UploadPlan<F>["rejected"] = [];
  let used = 0;
  for (const file of files) {
    if (maxFileSize > 0 && file.size > maxFileSize) {
      rejected.push({ file, reason: "too-large" });
    } else if (used + file.size > remaining) {
      rejected.push({ file, reason: "quota" });
    } else {
      used += file.size;
      accepted.push(file);
    }
  }
  return { accepted, rejected, remaining };
};

export const remainingLabel = (quota?: Quota): string => (quota ? formatSize(Math.max(0, quota.quota - quota.storage)) : "");

/** URL d'envoi : `/workspace/document` (ou `/workspace/zip` pour décompresser), dossier cible et qualité d'image conservée. */
export const uploadUrl = (opts: { zip?: boolean; parentId?: string; image?: boolean }): string => {
  const args: string[] = [];
  if (opts.image && !opts.zip) args.push("quality=1");
  if (opts.parentId) args.push(`parentId=${encodeURIComponent(opts.parentId)}`);
  return `/workspace/${opts.zip ? "zip" : "document"}${args.length ? `?${args.join("&")}` : ""}`;
};
