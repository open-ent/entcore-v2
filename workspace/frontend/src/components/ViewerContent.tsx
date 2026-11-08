import { LoadingScreen } from "@open-ent/react";
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { parseCsv, previewKind, previewUrlFor } from "../lib/viewer";
import { useTextContent } from "../services/queries";
import { WorkspaceElement } from "../lib/types";

const MAX_ROWS = 1000;

const Table = ({ text }: { text: string }) => {
  const { t } = useTranslation("workspace");
  const rows = useMemo(() => parseCsv(text), [text]);
  const [head, ...body] = rows;
  if (!head) return <p data-viewer-empty>{t("workspace.react.viewer.empty")}</p>;
  return (
    <div className="table-responsive w-100" data-viewer-table>
      <table className="table">
        <thead>
          <tr>
            {head.map((c, i) => (
              <th key={i}>{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {body.slice(0, MAX_ROWS).map((r, i) => (
            <tr key={i}>
              {r.map((c, j) => (
                <td key={j}>{c}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {body.length > MAX_ROWS && <p>{t("workspace.react.viewer.truncated", { count: MAX_ROWS })}</p>}
    </div>
  );
};

/** L'aperçu converti (PDF) vient d'un service de conversion : s'il est arrêté, le serveur répond une erreur JSON qu'il ne faut pas afficher telle quelle. */
const usePreviewAvailable = (url: string, enabled: boolean) =>
  useQuery({
    queryKey: ["workspace", "preview-check", url],
    queryFn: async () => {
      const res = await fetch(url, { credentials: "same-origin" });
      return res.ok && (res.headers.get("content-type") ?? "").includes("pdf");
    },
    enabled,
    retry: false,
  });

/**
 * Contenu d'un document, affiché selon son type : image, son, vidéo, PDF, bureautique (par l'aperçu converti du serveur),
 * tableur et CSV en tableau, texte, HTML isolé, et à défaut un simple téléchargement.
 * `source` : adresse du contenu quand il ne vient pas de l'espace documentaire (fichier NextCloud) ; pas d'aperçu converti dans ce cas.
 */
export const Content = ({ element, source }: { element: WorkspaceElement; source?: string }) => {
  const { t } = useTranslation("workspace");
  const kind = previewKind(element.metadata?.["content-type"], element.name);
  const url = source ?? previewUrlFor(element);
  const textual = kind === "text" || kind === "html" || kind === "table";
  const text = useTextContent(textual ? url : undefined);
  const preview = usePreviewAvailable(url, kind === "office" && !source);
  const version = element.modified ?? "";

  if (textual && text.isLoading) return <LoadingScreen />;
  if (kind === "office" && !source && preview.isLoading) return <LoadingScreen />;
  if (kind === "office" && (source || !preview.data)) {
    return (
      <div data-viewer-unavailable className="text-center">
        <p className="text-danger" role="alert">
          {t("workspace.react.viewer.preview.unavailable")}
        </p>
        <a className="btn btn-primary" href={source ?? `/workspace/document/${element._id}`}>
          {t("file.viewer.download")}
        </a>
      </div>
    );
  }
  if (textual && text.isError)
    return (
      <p className="text-danger" role="alert" data-viewer-error>
        {t("workspace.react.viewer.unavailable")}
      </p>
    );

  switch (kind) {
    case "image":
      return <img src={`${url}${url.includes("?") ? "&" : "?"}v=${encodeURIComponent(version)}`} alt={element.name} data-viewer="image" />;
    case "audio":
      return <audio controls src={url} data-viewer="audio" />;
    case "video":
      return <video controls src={url} data-viewer="video" />;
    case "pdf":
    case "office":
      return <iframe title={element.name} src={url} data-viewer={kind} />;
    case "table":
      return <Table text={text.data ?? ""} />;
    case "html":
      // Contenu non fiable : isolé dans un cadre sans aucun droit (ni scripts, ni accès à l'ENT).
      return <iframe title={element.name} sandbox="" srcDoc={text.data ?? ""} data-viewer="html" />;
    case "text":
      return (
        <pre className="p-12 w-100" style={{ whiteSpace: "pre-wrap" }} data-viewer="text">
          {text.data}
        </pre>
      );
    default:
      return (
        <p data-viewer="none">
          <a className="btn btn-primary" href={source ?? `/workspace/document/${element._id}`}>
            {t("file.viewer.download")}
          </a>
        </p>
      );
  }
};
