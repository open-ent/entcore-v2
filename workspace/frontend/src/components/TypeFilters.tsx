import { useTranslation } from "react-i18next";
import { FILTER_LABEL_KEY, TypeFilterKey } from "../lib/typeFilter";

/**
 * Puces de filtre au-dessus de la liste : « Tout · 9 », « Dossiers · 3 », « Vidéos · 5 »… Seules les familles présentes
 * dans l'emplacement courant sont proposées ; rien ne s'affiche quand il n'y a qu'une famille (filtrer n'y sert à rien).
 */
export const TypeFilters = ({
  counts,
  value,
  onChange,
}: {
  counts: Array<{ key: TypeFilterKey; count: number }>;
  value: TypeFilterKey;
  onChange: (key: TypeFilterKey) => void;
}) => {
  const { t } = useTranslation("workspace");
  if (counts.length <= 2) return null;
  return (
    <nav className="d-flex flex-wrap gap-8 mb-16" aria-label={t("workspace.react.filter.label")} data-type-filters>
      {counts.map(({ key, count }) => {
        const active = key === value;
        return (
          <button
            key={key}
            type="button"
            className={`btn btn-sm rounded-pill ${active ? "btn-primary" : "btn-tertiary"}`}
            aria-pressed={active}
            data-type-filter={key}
            onClick={() => onChange(key)}
          >
            {t(FILTER_LABEL_KEY[key])} · {count}
          </button>
        );
      })}
    </nav>
  );
};
