import { AppHeader, ImageEditor, LoadingScreen, useEdificeClient } from "@open-ent/react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { readFlags } from "../lib/flags";
import { canOpenInGeogebra, canOpenInLool, canOpenInScratch, geogebraUrl, LOOL_OPEN, loolUrl, scratchUrl } from "../lib/integrations";
import { rightsOn } from "../lib/rights";
import { parseCsv, previewKind, previewUrlFor } from "../lib/viewer";
import { useElement, useLoolContext, useTextContent } from "../services/queries";
import { updateImage } from "../services/workspace.service";
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

/** `source` : adresse du contenu quand il ne vient pas de l'espace documentaire (fichier NextCloud) ; pas d'aperçu converti dans ce cas. */
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
      <div data-viewer-unavailable>
        <p className="text-danger" role="alert">
          {t("workspace.react.viewer.preview.unavailable")}
        </p>
        <a className="btn btn-primary" href={source ?? `/workspace/document/${element._id}`}>
          {t("file.viewer.download")}
        </a>
      </div>
    );
  }
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
          <a className="btn btn-primary" href={source ?? `/workspace/document/${element._id}`}>
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
  const qc = useQueryClient();
  const { user } = useEdificeClient();
  const flags = useMemo(() => readFlags(), []);
  const workflows = new Set(((user as { authorizedActions?: Array<{ name: string }> } | undefined)?.authorizedActions ?? []).map((a) => a.name));
  const lool = useLoolContext(flags.enableLool && workflows.has(LOOL_OPEN));
  // `?edit=image` (action « Retoucher l'image » de la liste) ouvre directement la retouche
  const [editingImage, setEditingImage] = useState(() => new URLSearchParams(location.search).get("edit") === "image");
  const [editError, setEditError] = useState(false);

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
        <Link to="/owner">{t("documents")}</Link>
      </>
    );
  }
  const el = element.data;
  const rights = rightsOn(el, user?.userId, (user as { groupsIds?: string[] } | undefined)?.groupsIds ?? []);
  return (
    <>
      <AppHeader>
        <h1 className="h2">{t("workspace.title")}</h1>
      </AppHeader>
      <h2 className="h3 mb-12" data-viewer-title>
        {el.name}
      </h2>
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
          {t("workspace.react.viewer.fullscreen")}
        </button>
        {rights.contrib && previewKind(el.metadata?.["content-type"], el.name) === "image" && (
          <button type="button" className="btn btn-outline-primary" onClick={() => setEditingImage(true)} data-viewer-edit-image>
            {t("workspace.react.edit.image")}
          </button>
        )}
        {flags.enableLool && workflows.has(LOOL_OPEN) && canOpenInLool(lool.data, el) && (
          <button type="button" className="btn btn-outline-primary" onClick={() => window.open(loolUrl(el._id))} data-viewer-open-lool>
            {t("workspace.external.open")}
          </button>
        )}
        {flags.enableScratch && canOpenInScratch(el) && (
          <button type="button" className="btn btn-outline-primary" onClick={() => window.open(scratchUrl(el._id))} data-viewer-open-scratch>
            {t("workspace.external.open")}
          </button>
        )}
        {flags.enableGeogebra && canOpenInGeogebra(el) && (
          <button type="button" className="btn btn-outline-primary" onClick={() => window.open(geogebraUrl(el))} data-viewer-open-geogebra>
            {t("workspace.external.open")}
          </button>
        )}
        <a className="btn btn-primary" href={`/workspace/document/${el._id}`} data-viewer-download>
          {t("workspace.download")}
        </a>
      </div>
      {editError && (
        <p className="text-danger" role="alert" data-viewer-edit-error>
          {t("workspace.react.action.failed")}
        </p>
      )}
      <div ref={box} style={{ background: "#fff" }}>
        <Content element={el} />
      </div>
      {editingImage && rights.contrib && (
        <ImageEditor
          isOpen
          image={`/workspace/document/${el._id}`}
          onCancel={() => setEditingImage(false)}
          onError={() => setEditError(true)}
          onSave={async ({ blob, legend, altText }) => {
            try {
              setEditError(false);
              await updateImage(el._id, blob, el.name, altText, legend);
              setEditingImage(false);
              await Promise.all(["element", "content"].map((k) => qc.invalidateQueries({ queryKey: ["workspace", k] })));
            } catch {
              setEditError(true);
            }
          }}
        />
      )}
    </>
  );
};
