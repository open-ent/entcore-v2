import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { BarLayout, groupActions } from "../lib/actionGroups";

/**
 * Barre de la sélection, sur une ligne : compteur et ✕, actions fréquentes en boutons, menus déroulants (Partager, Organiser, Plus),
 * puis les actions destructrices à droite en couleur d'alerte. Commune à l'espace documentaire et à NextCloud ;
 * `attr` donne les noms d'attributs de test de chacune (`data-action-bar` / `data-action`, ou `data-nc-action-bar` / `data-nc-action`).
 */
export const SelectionBar = <T extends string>({
  actions,
  layout,
  labels,
  count,
  attr,
  busy = false,
  onAction,
  onClear,
}: {
  actions: T[];
  layout: BarLayout<T>;
  /** Clé de traduction de chaque action. */
  labels: Record<T, string>;
  count: number;
  attr: { bar: string; action: string };
  busy?: boolean;
  onAction: (id: T) => void;
  onClear?: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const [open, setOpen] = useState<string | null>(null);
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => !root.current?.contains(e.target as Node) && setOpen(null);
    const esc = (e: KeyboardEvent) => e.key === "Escape" && setOpen(null);
    document.addEventListener("mousedown", away);
    document.addEventListener("keydown", esc);
    return () => {
      document.removeEventListener("mousedown", away);
      document.removeEventListener("keydown", esc);
    };
  }, [open]);
  if (actions.length === 0) return null;
  const { entries, danger } = groupActions(actions, layout);
  const run = (id: T) => {
    setOpen(null);
    onAction(id);
  };
  const button = (id: T, cls: string) => (
    <button key={id} type="button" className={`btn ${cls} btn-sm`} disabled={busy} {...{ [attr.action]: id }} onClick={() => run(id)}>
      {t(labels[id])}
    </button>
  );
  return (
    <div ref={root} className="d-flex flex-wrap gap-8 align-items-center mb-16" role="toolbar" {...{ [attr.bar]: "" }}>
      <span data-selection-count>{t("workspace.react.selected", { count })}</span>
      {onClear && (
        <button type="button" className="btn btn-outline-secondary btn-sm" onClick={onClear} aria-label={t("workspace.react.select.none")} title={t("workspace.react.select.none")} data-selection-clear>
          ✕
        </button>
      )}
      <span aria-hidden="true" style={{ borderLeft: "1px solid #ced4da", alignSelf: "stretch" }} />
      {entries.map((e) =>
        e.kind === "button" ? (
          button(e.id, "btn-outline-primary")
        ) : (
          <div key={e.key} style={{ position: "relative" }}>
            <button
              type="button"
              className="btn btn-outline-primary btn-sm"
              aria-haspopup="menu"
              aria-expanded={open === e.key}
              disabled={busy}
              data-menu-toggle={e.key}
              data-menu-actions={e.ids.join(" ")}
              onClick={() => setOpen(open === e.key ? null : e.key)}
            >
              {t(e.label)} ▾
            </button>
            {open === e.key && (
              <div role="menu" className="card shadow p-4" style={{ position: "absolute", left: 0, top: "100%", marginTop: 4, minWidth: 240, background: "#fff", zIndex: 1050 }} data-menu={e.key}>
                {e.ids.map((id) => (
                  <button
                    key={id}
                    type="button"
                    role="menuitem"
                    disabled={busy}
                    {...{ [attr.action]: id }}
                    onClick={() => run(id)}
                    style={{ display: "block", width: "100%", textAlign: "left", border: 0, background: "transparent", color: "#243242", padding: "8px 10px", borderRadius: 6, cursor: "pointer", font: "inherit" }}
                  >
                    {t(labels[id])}
                  </button>
                ))}
              </div>
            )}
          </div>
        ),
      )}
      {danger.length > 0 && <span style={{ marginLeft: "auto" }} />}
      {danger.map((id) => button(id, "btn-outline-danger"))}
    </div>
  );
};
