import { AppHeader, LoadingScreen, useEdificeClient } from "@open-ent/react";
import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { downloadUrl, parentPath } from "../lib/nextcloud";
import { WorkspaceElement } from "../lib/types";
import { useNextcloudList } from "../services/queries";
import { Content } from "./Viewer";

/**
 * Visionneuse d'un fichier NextCloud, dans l'application (comme celle des documents personnels) : texte, tableau, PDF, image, son,
 * vidéo ; plein écran, téléchargement, précédent / suivant parmi les fichiers du dossier. Adresse : `#/nextcloud-view?path=Cours/a.pdf`.
 */
export const NextcloudViewer = () => {
  const { t } = useTranslation("workspace");
  const [params] = useSearchParams();
  const path = params.get("path") ?? "";
  const folder = parentPath(path);
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useEdificeClient();
  const userId = user?.userId;
  const list = useNextcloudList(userId, folder, false, !!userId);
  const box = useRef<HTMLDivElement>(null);
  const back = `/nextcloud${folder ? `?path=${encodeURIComponent(folder)}` : ""}`;
  const files = (list.data ?? []).filter((d) => !d.isFolder);
  const index = files.findIndex((d) => d.path === path);
  const doc = files[index];
  const prev = index > 0 ? files[index - 1] : undefined;
  const next = index >= 0 && index < files.length - 1 ? files[index + 1] : undefined;
  const go = (d?: { path: string }) => d && navigate(`/nextcloud-view?path=${encodeURIComponent(d.path)}`, { replace: true, state: location.state });
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "ArrowLeft") go(prev);
      if (e.key === "ArrowRight") go(next);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  if (!userId || list.isLoading) return <LoadingScreen />;
  if (!doc) {
    return (
      <>
        <p className="text-danger" role="alert" data-viewer-error>
          {t("workspace.react.viewer.unavailable")}
        </p>
        <Link to={back}>{t("nextcloud.documents")}</Link>
      </>
    );
  }
  // Le contenu s'affiche à partir de l'adresse de téléchargement « en ligne » du connecteur ; pas d'aperçu converti pour NextCloud.
  const element: WorkspaceElement = { _id: doc.path, name: doc.name, eType: "file", eParent: null, owner: "", ownerName: doc.ownerName, modified: doc.lastModified, metadata: { "content-type": doc.contentType, size: doc.size } as never };
  return (
    <>
      <AppHeader>
        <h1 className="h2">{t("workspace.title")}</h1>
      </AppHeader>
      <h2 className="h3 mb-12" data-viewer-title>
        {doc.name}
      </h2>
      <div className="d-flex gap-12 align-items-center mb-16" data-viewer-toolbar>
        <button type="button" className="btn btn-outline-primary" onClick={() => navigate(back)} data-viewer-back>
          {t("workspace.react.viewer.back")}
        </button>
        {doc.ownerName && (
          <small>
            {t("owner")} : <strong>{doc.ownerName}</strong>
          </small>
        )}
        <span className="flex-fill" />
        {files.length > 1 && (
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
        <a className="btn btn-primary" href={downloadUrl(userId, doc)} data-viewer-download>
          {t("workspace.download")}
        </a>
      </div>
      <div ref={box} style={{ background: "#fff" }}>
        <Content element={element} source={downloadUrl(userId, doc, true)} />
      </div>
    </>
  );
};
