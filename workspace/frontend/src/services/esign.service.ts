import { SignatureReport } from "../lib/signature";
import { xsrfToken } from "./workspace.service";

const toBase64 = (bytes: Uint8Array): string => {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
};

/**
 * Fait vérifier la signature d'un document de l'espace documentaire par le module esign. Rejette
 * avec une clé de traduction : esign absent de la plateforme, document illisible…
 */
export const verifySignature = async (docId: string): Promise<SignatureReport> => {
  const file = await fetch(`/workspace/document/${docId}`, { credentials: "same-origin" });
  if (!file.ok) throw "workspace.signature.load.failed";
  const document = toBase64(new Uint8Array(await file.arrayBuffer()));
  let res: Response;
  try {
    res = await fetch("/esign/validation", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", Accept: "application/json", "X-XSRF-TOKEN": xsrfToken() },
      body: JSON.stringify({ document }),
    });
  } catch {
    throw "workspace.signature.unavailable";
  }
  if (res.status === 404 || res.status === 502 || res.status === 503) throw "workspace.signature.unavailable";
  if (!res.ok) throw "workspace.signature.failed";
  return (await res.json()) as SignatureReport;
};
