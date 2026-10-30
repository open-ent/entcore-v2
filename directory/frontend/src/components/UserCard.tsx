import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { profileRows } from "../lib/profile";
import { directoryService } from "../services/account.service";
import { Visible } from "../lib/directory";

/** Fiche d'un utilisateur de l'annuaire : identité, rattachements, devise, centres d'intérêt et coordonnées rendues publiques. */
export const UserCard = ({ user, onBack }: { user: Visible; onBack: () => void }) => {
  const { t } = useTranslation("directory");
  const person = useQuery({ queryKey: ["person", user.id], queryFn: () => directoryService.person(user.id, user.profile ?? "") });
  const p = person.data;
  // seules les coordonnées présentes (donc publiques) s'affichent ; pas de ligne d'identifiant dans l'annuaire
  const rows = profileRows(undefined, p).filter((r) => ["email", "mobile", "tel", "address", "birthDate"].includes(r.key));
  return (
    <article data-directory-card={user.id}>
      <button type="button" className="btn btn-link p-0 mb-16" onClick={onBack} data-directory-back>
        ← {t("userBook.directory.backToResults")}
      </button>
      <div className="d-flex gap-16 align-items-center mb-16">
        <img src={`/userbook/avatar/${user.id}?thumbnail=100x100`} alt="" width={80} height={80} style={{ borderRadius: "50%", objectFit: "cover" }} />
        <div>
          <h2 className="h3 mb-0">{p?.displayName ?? user.displayName}</h2>
          {user.profile && <span className="text-muted">{t(user.profile)}</span>}
          {(user.subjects?.length ?? 0) > 0 && <div className="text-muted">{user.subjects?.join(", ")}</div>}
        </div>
      </div>
      {p?.motto && <p className="fst-italic">« {p.motto} »</p>}
      {(p?.schools?.length ?? 0) > 0 && (
        <ul>
          {p?.schools?.map((s) => (
            <li key={s.id}>
              {s.name}
              {(s.classes ?? []).length > 0 && <> — {s.classes?.map((c) => (typeof c === "string" ? c : c.name)).filter(Boolean).join(", ")}</>}
            </li>
          ))}
        </ul>
      )}
      {rows.length > 0 && (
        <dl>
          {rows.map((r) => (
            <div key={r.key} className="d-flex gap-16 mb-4">
              <dt style={{ width: 200, fontWeight: 600 }}>{t(r.label)}</dt>
              <dd className="mb-0">{r.value}</dd>
            </div>
          ))}
        </dl>
      )}
      {(p?.hobbies ?? []).filter((h) => h.values).length > 0 && (
        <section>
          <h3 className="h5">{t("userBook.interests")}</h3>
          <ul>
            {p?.hobbies
              ?.filter((h) => h.values)
              .map((h) => (
                <li key={h.category}>
                  {t(`userBook.hobby.${h.category}`)} : {h.values}
                </li>
              ))}
          </ul>
        </section>
      )}
    </article>
  );
};
