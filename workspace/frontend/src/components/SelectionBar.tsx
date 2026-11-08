import { Button, Dropdown } from "@open-ent/react";
import { useTranslation } from "react-i18next";
import { BarLayout, groupActions } from "../lib/actionGroups";

/**
 * Barre de la sélection, sur une ligne : compteur (avec le poids total quand on le connaît) et ✕, actions fréquentes en
 * boutons, menus déroulants (Partager, Organiser, Plus), puis les actions destructrices à droite en couleur d'alerte.
 * Commune à l'espace documentaire et à NextCloud ; `attr` donne les noms d'attributs de test de chacune
 * (`data-action-bar` / `data-action`, ou `data-nc-action-bar` / `data-nc-action`).
 */
export const SelectionBar = <T extends string>({
  actions,
  layout,
  labels,
  count,
  sizeLabel,
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
  /** Poids total de la sélection, déjà mis en forme (« 46,9 Mo ») ; absent quand il n'a pas de sens. */
  sizeLabel?: string;
  attr: { bar: string; action: string };
  busy?: boolean;
  onAction: (id: T) => void;
  onClear?: () => void;
}) => {
  const { t } = useTranslation("workspace");
  if (actions.length === 0) return null;
  const { entries, danger } = groupActions(actions, layout);
  const button = (id: T, color: "primary" | "danger") => (
    <Button
      key={id}
      type="button"
      color={color}
      variant="ghost"
      size="sm"
      disabled={busy}
      {...{ [attr.action]: id }}
      onClick={() => onAction(id)}
    >
      {t(labels[id])}
    </Button>
  );
  return (
    <div
      className="card card-body flex-row flex-wrap gap-8 align-items-center p-8 mb-16"
      role="toolbar"
      aria-label={t("workspace.react.selected", { count })}
      {...{ [attr.bar]: "" }}
    >
      {onClear && (
        <Button
          type="button"
          color="tertiary"
          variant="ghost"
          size="sm"
          onClick={onClear}
          aria-label={t("workspace.react.select.none")}
          title={t("workspace.react.select.none")}
          data-selection-clear
        >
          ✕
        </Button>
      )}
      <span className="fw-bold" data-selection-count>
        {t("workspace.react.selected", { count })}
        {sizeLabel ? ` · ${sizeLabel}` : ""}
      </span>
      <span className="vr mx-4" aria-hidden="true" />
      {entries.map((e) =>
        e.kind === "button" ? (
          button(e.id, "primary")
        ) : (
          <Dropdown key={e.key}>
            <Dropdown.Trigger
              label={t(e.label)}
              variant="ghost"
              size="sm"
              disabled={busy}
              data-menu-toggle={e.key}
              data-menu-actions={e.ids.join(" ")}
            />
            <Dropdown.Menu data-menu={e.key}>
              {e.ids.map((id) => (
                <Dropdown.Item key={id} disabled={busy} {...{ [attr.action]: id }} onClick={() => onAction(id)}>
                  {t(labels[id])}
                </Dropdown.Item>
              ))}
            </Dropdown.Menu>
          </Dropdown>
        ),
      )}
      {danger.length > 0 && <span className="ms-auto" />}
      {danger.map((id) => button(id, "danger"))}
    </div>
  );
};
