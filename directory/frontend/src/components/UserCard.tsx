import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { profileRows } from "../lib/profile";
import { directoryService } from "../services/account.service";
import { Visible } from "../lib/directory";
import { onAvatarError } from "../lib/avatar";

/** Fiche d'un utilisateur de l'annuaire : identité, rattachements, devise, centres d'intérêt et coordonnées rendues publiques. */
export const UserCard = ({ user, onBack }: { user: Visible; onBack: () => void }) => {
  const { t } = useTranslation("directory");
  const person = useQuery({ queryKey: ["person", user.id], queryFn: () => directoryService.person(user.id, user.profile ?? "") });
  const p = person.data;
  // seules les coordonnées présentes (donc publiques) s'affichent ; pas de ligne d'identifiant dans l'annuaire
  const rows = profileRows(undefined, p).filter((r) => ["email", "mobile", "tel", "address", "birthDate"].includes(r.key));
  const hobbies = (p?.hobbies ?? []).filter((h) => h.values);
  const schools = p?.schools ?? [];
  return (
    <article data-directory-card={user.id}>
      <button type="button" className="ann-back" onClick={onBack} data-directory-back>
        ← {t("userBook.directory.backToResults")}
      </button>
      <header className="ann-card-head" data-profile={user.profile}>
        <img
          className="ann-avatar"
          src={`/userbook/avatar/${user.id}?thumbnail=200x200`}
          alt=""
          width={96}
          height={96}
          onError={onAvatarError}
        />
        <div>
          <h2>{p?.displayName ?? user.displayName}</h2>
          {user.profile && <span className="ann-badge">{t(user.profile)}</span>}
          {(user.subjects?.length ?? 0) > 0 && <div className="ann-subjects">{user.subjects?.join(", ")}</div>}
          {p?.motto && <p className="ann-motto">« {p.motto} »</p>}
        </div>
      </header>
      <div className="ann-card-body">
        {schools.length > 0 && (
          <section>
            <h3>{t("directory.attachments")}</h3>
            <ul>
              {schools.map((s) => {
                const classes = (s.classes ?? []).map((c) => (typeof c === "string" ? c : c.name)).filter(Boolean);
                return (
                  <li key={s.id}>
                    {s.name}
                    {classes.length > 0 && <span>{classes.join(", ")}</span>}
                  </li>
                );
              })}
            </ul>
          </section>
        )}
        {rows.length > 0 && (
          <section>
            <h3>{t("directory.contact")}</h3>
            <dl>
              {rows.map((r) => (
                <div key={r.key}>
                  <dt>{t(r.label)}</dt>
                  <dd>{r.value}</dd>
                </div>
              ))}
            </dl>
          </section>
        )}
        {hobbies.length > 0 && (
          <section>
            <h3>{t("userBook.interests")}</h3>
            <dl>
              {hobbies.map((h) => (
                <div key={h.category}>
                  <dt>{t(`userBook.hobby.${h.category}`)}</dt>
                  <dd>{h.values}</dd>
                </div>
              ))}
            </dl>
          </section>
        )}
      </div>
    </article>
  );
};
