import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

/**
 * Bascule permanente entre la nouvelle (React) et l'ancienne (AngularJS) interface — même comportement que celle du socle
 * (Layout/UiSwitchFab) et que `ui-fab.js` côté AngularJS : bouton flottant, dérogation d'adresse `?ui=` seule, aucune préférence écrite.
 */
export const UiSwitch = () => {
  const { t } = useTranslation("workspace");
  const [open, setOpen] = useState(false);
  // Le bandeau du module occupe lui aussi le coin en bas à droite : le bouton se pose juste au-dessus au lieu de disparaître.
  const [bottom, setBottom] = useState(16);
  useEffect(() => {
    const measure = () => {
      const banner = document.querySelector<HTMLElement>("[data-ui-banner]");
      setBottom(banner && banner.getClientRects().length > 0 ? Math.round(window.innerHeight - banner.getBoundingClientRect().top + 8) : 16);
    };
    measure();
    const observer = new MutationObserver(measure);
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ["hidden", "class", "style"] });
    window.addEventListener("resize", measure);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", measure);
    };
  }, []);
  const item = { display: "block", width: "100%", textAlign: "left", border: 0, background: "transparent", color: "#243242", padding: "8px 10px", borderRadius: 6, cursor: "pointer", font: "inherit" } as const;
  return (
    <div className="openent-ui-fab" data-ui-fab style={{ position: "fixed", right: 16, bottom, zIndex: 1040 }}>
      {open && (
        <div role="menu" className="card shadow p-4" style={{ position: "absolute", right: 0, bottom: 48, minWidth: 220, background: "#fff" }}>
          <button type="button" role="menuitem" style={item} aria-current="true" onClick={() => setOpen(false)} data-ui-current>
            <strong>{t("workspace.react.ui.new")}</strong> ({t("workspace.react.ui.current")})
          </button>
          <button
            type="button"
            role="menuitem"
            style={item}
            data-ui-angular
            onClick={() => {
              window.location.href = `${window.location.pathname}?ui=angular`;
            }}
          >
            {t("workspace.react.ui.old")}
          </button>
        </div>
      )}
      <button type="button" className="btn btn-secondary rounded-pill shadow" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} data-ui-fab-toggle>
        ⇄ {t("workspace.react.ui.label")}
      </button>
    </div>
  );
};
