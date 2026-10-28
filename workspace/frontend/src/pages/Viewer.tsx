import { AppHeader, LoadingScreen } from "@open-ent/react";
import { useEffect, useMemo, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { parseCsv, previewKind, previewUrlFor } from "../lib/viewer";
import { useElement, useTextContent } from "../services/queries";
import { WorkspaceElement } from "../lib/types";

const MAX_ROWS = 1000;

const Table = ({ text }: { text: string }) => {
  const { t } = useTranslation("workspace");
  const rows = useMemo(() => parseCsv(text), [text]);
  const [head, ...body] = rows;
  if (!head) return <p data-viewer-empty>{t("workspace.react.viewer.empty")}</p>;
  return (
    <div className="table-responsive" data-viewer-table>
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

const Content = ({ element }: { element: WorkspaceElement }) => {
  const { t } = useTranslation("workspace");
  const kind = previewKind(element.metadata?.["content-type"], element.name);
  const url = previewUrlFor(element);
  const textual = kind === "text" || kind === "html" || kind === "table";
  const text = useTextContent(textual ? url : undefined);
  const version = element.modified ?? "";

  if (textual && text.isLoading) return <LoadingScreen />;
  if (textual && text.isError) return <p className="text-danger" role="alert" data-viewer-error>{t("workspace.react.viewer.unavailable")}</p>;

  switch (kind) {
    case "image":
      return <img src={`${url}?v=${encodeURIComponent(version)}`} alt={element.name} style={{ maxWidth: "100%" }} data-viewer="image" />;
    case "audio":
      return <audio controls src={url} data-viewer="audio" />;
    case "video":
      return <video controls src={url} style={{ maxWidth: "100%" }} data-viewer="video" />;
    case "pdf":
    case "office":
      return <iframe title={element.name} src={url} style={{ width: "100%", height: "70vh", border: 0 }} data-viewer={kind} />;
    case "table":
      return <Table text={text.data ?? ""} />;
    case "html":
      // Contenu non fiable : isolé dans un cadre sans aucun droit (ni scripts, ni accès à l'ENT).
      return <iframe title={element.name} sandbox="" srcDoc={text.data ?? ""} style={{ width: "100%", height: "70vh", border: 0 }} data-viewer="html" />;
    case "text":
      return <pre className="p-12" style={{ whiteSpace: "pre-wrap" }} data-viewer="text">{text.data}</pre>;
    default:
      return (
        <p data-viewer="none">
          <a className="btn btn-primary" href={`/workspace/document/${element._id}`}>
            {t("file.viewer.download")}
          </a>
        </p>
      );
  }
};

/** Visionneuse d'un document : affichage selon le type, téléchargement, plein écran, précédent / suivant parmi les documents du dossier. */
export const Viewer = () => {
  const { t } = useTranslation("workspace");
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const nav = (location.state as { ids?: string[]; from?: string } | null) ?? {};
  const siblings = nav.ids ?? [];
  const from = nav.from ?? "/owner";
  const index = siblings.indexOf(id ?? "");
  const prev = index > 0 ? siblings[index - 1] : undefined;
  const next = index >= 0 && index < siblings.length - 1 ? siblings[index + 1] : undefined;
  const element = useElement(id);
  const box = useRef<HTMLDivElement>(null);

  const go = (target?: string) => target && navigate(`/view/${target}`, { state: { ids: siblings, from }, replace: true });
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "ArrowLeft") go(prev);
      if (e.key === "ArrowRight") go(next);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const back = () => navigate(from);

  if (element.isLoading) return <LoadingScreen />;
  if (element.isError || !element.data) {
    return (
      <>
        <p className="text-danger" role="alert" data-viewer-error>
          {t("workspace.react.viewer.unavailable")}
        </p>
        <Link to="/owner">{t("workspace.react.tree.owner")}</Link>
      </>
    );
  }
  const el = element.data;
  return (
    <>
      <AppHeader>
        <h1 className="h2" data-viewer-title>
          {el.name}
        </h1>
      </AppHeader>
      <div className="d-flex gap-12 align-items-center mb-16" data-viewer-toolbar>
        <button type="button" className="btn btn-outline-primary" onClick={back} data-viewer-back>
          {t("workspace.react.viewer.back")}
        </button>
        <small>
          {t("owner")} : <strong>{el.ownerName}</strong>
        </small>
        <span className="flex-fill" />
        {siblings.length > 1 && (
          <>
            <button type="button" className="btn btn-outline-secondary" disabled={!prev} onClick={() => go(prev)} data-viewer-prev>
              {t("workspace.react.viewer.previous")}
            </button>
            <button type="button" className="btn btn-outline-secondary" disabled={!next} onClick={() => go(next)} data-viewer-next>
              {t("workspace.react.viewer.next")}
            </button>
          </>
        )}
        <button type="button" className="btn btn-outline-primary" onClick={() => box.current?.requestFullscreen?.()} data-viewer-fullscreen>
          {t("preview")}
        </button>
        <a className="btn btn-primary" href={`/workspace/document/${el._id}`} data-viewer-download>
          {t("download")}
        </a>
      </div>
      <div ref={box} style={{ background: "#fff" }}>
        <Content element={el} />
      </div>
    </>
  );
};
