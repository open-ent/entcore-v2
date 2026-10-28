import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { loolCreateUrl, loolReaction, parseMessage } from "../lib/integrations";

/**
 * Création d'un document bureautique : le connecteur fournit sa propre fenêtre (iframe) qui dialogue avec la page par
 * messages — elle demande le dossier courant, signale qu'elle est prête, demande un rechargement, puis se ferme.
 */
export const LoolCreateModal = ({
  isOpen,
  folderId,
  onClose,
  onResync,
}: {
  isOpen: boolean;
  folderId?: string;
  onClose: () => void;
  onResync: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    setLoaded(false);
    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      switch (loolReaction(parseMessage(event.data))) {
        case "getFolder":
          window.postMessage(JSON.stringify({ id: "lool@getFolderResponse", ...(folderId ? { folderId } : {}) }), window.location.origin);
          break;
        case "resync":
          onResync();
          break;
        case "close":
          onResync();
          onClose();
          break;
        case "ready":
          setLoaded(true);
          break;
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [isOpen, folderId, onClose, onResync]);

  if (!isOpen) return null;
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t("workspace.lool.create-document")}
      data-lool-modal
      onClick={(e) => e.target === e.currentTarget && onClose()}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,.5)", zIndex: 2000, display: "flex", alignItems: "center", justifyContent: "center" }}
    >
      <div style={{ background: "#fff", borderRadius: 8, width: "min(900px, 95vw)", height: "min(640px, 90vh)", position: "relative" }}>
        {!loaded && <p className="p-24" data-lool-loading>…</p>}
        <iframe
          title={t("workspace.lool.create-document")}
          src={loolCreateUrl(folderId)}
          style={{ width: "100%", height: "100%", border: 0, visibility: loaded ? "visible" : "hidden", position: "absolute", inset: 0 }}
          data-lool-iframe
        />
      </div>
    </div>
  );
};
