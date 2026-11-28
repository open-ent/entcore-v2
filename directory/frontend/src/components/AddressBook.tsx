import { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { groupByInitial, Visible } from "../lib/directory";
import { onAvatarError } from "../lib/avatar";

/** Une personne de l'annuaire : avatar cerclé de la couleur de son profil, nom et profil. */
export const PersonEntry = ({ user, onOpen, ...data }: { user: Visible; onOpen: (u: Visible) => void } & Record<`data-${string}`, string>) => {
  const { t } = useTranslation("directory");
  return (
    <button type="button" className="ann-entry" data-profile={user.profile} onClick={() => onOpen(user)} {...data}>
      <img
        className="ann-avatar"
        src={`/userbook/avatar/${user.id}?thumbnail=100x100`}
        onError={onAvatarError}
        alt=""
        width={44}
        height={44}
        loading="lazy"
      />
      <span className="ann-entry-text">
        <strong title={user.displayName}>{user.displayName}</strong>
        {user.profile && <small>{t(user.profile)}</small>}
      </span>
    </button>
  );
};

/** Icône de groupe, à la place de l'avatar. */
export const GroupMark = () => (
  <span className="ann-group-mark" aria-hidden="true">
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="9" cy="8" r="3.5" />
      <path d="M2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6" />
      <path d="M16 4.6a3.5 3.5 0 0 1 0 6.8M18.5 14.4c1.9.8 3 2.8 3 5.6" />
    </svg>
  </span>
);

/**
 * Liste rangée en carnet d'adresses : une section par initiale, et une réglette en tête qui mène à chaque lettre présente.
 * `count` est la phrase du compteur (« 42 personnes ») ; `renderItem` rend une entrée.
 */
export function AddressBook<T extends { id: string }>({
  items,
  nameOf,
  count,
  renderItem,
  idPrefix,
  letterTag: Letter = "h2",
  ...data
}: {
  items: T[];
  nameOf: (item: T) => string;
  count?: ReactNode;
  /** niveau du titre des lettres, sous le titre de la page ou de la fiche */
  letterTag?: "h2" | "h3";
  renderItem: (item: T) => ReactNode;
  idPrefix: string;
} & Record<`data-${string}`, string>) {
  const { t } = useTranslation("directory");
  const sections = groupByInitial(items, nameOf);
  const anchor = (letter: string) => `${idPrefix}-${letter === "#" ? "other" : letter}`;
  return (
    <div>
      <div className="ann-results-head">
        {count ? <p className="ann-count" aria-live="polite">{count}</p> : <span />}
        {sections.length > 1 && (
          <nav aria-label={t("directory.alphabet", { defaultValue: "Aller à la lettre" })}>
            <ul className="ann-rail">
              {sections.map((s) => (
                <li key={s.letter}>
                  <a href={`#${anchor(s.letter)}`} onClick={(e) => {
                    // l'ancre ne doit pas changer l'adresse : elle rouvrirait une fiche (`#<id>`) au rechargement
                    e.preventDefault();
                    document.getElementById(anchor(s.letter))?.scrollIntoView({ behavior: "smooth", block: "start" });
                  }}>
                    {s.letter}
                  </a>
                </li>
              ))}
            </ul>
          </nav>
        )}
      </div>
      <div {...data}>
        {sections.map((s) => (
          <section key={s.letter} id={anchor(s.letter)} className="ann-section" aria-labelledby={`${anchor(s.letter)}-h`}>
            <Letter id={`${anchor(s.letter)}-h`} className="ann-letter">{s.letter}</Letter>
            <ul className="ann-list">
              {s.items.map((item) => (
                <li key={item.id}>{renderItem(item)}</li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  );
}
