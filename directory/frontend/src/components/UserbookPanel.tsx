import { Button } from "@open-ent/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { hobbyIsPublic, isVisible, MOTTO_MAX, mottoChanged, nextVisibility, showsChildren, showsRelatives, toggledHobbyVisibility } from "../lib/userbook";
import { accountService, Person } from "../services/account.service";

/** Œil ouvert / fermé : visibilité d'une information pour les autres utilisateurs. */
export const VisibilityToggle = ({ visible, onToggle, id }: { visible: boolean; onToggle: () => void; id: string }) => {
  const { t } = useTranslation("directory");
  const label = t(visible ? "public" : "prive");
  return (
    <button type="button" className="btn btn-link p-0 ms-8" title={label} aria-label={label} aria-pressed={visible} onClick={onToggle} data-visibility={id}>
      {visible ? "👁" : "🚫"}
    </button>
  );
};

/** Devise, humeur, centres d'intérêt, divers, enfants ou responsables : la fiche « userbook » de Mon compte. */
export const UserbookPanel = ({ userId, person, type, mottoAllowed }: { userId: string; person: Person | undefined; type: string | undefined; mottoAllowed: boolean }) => {
  const { t } = useTranslation("directory");
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: ["account"] });

  const [motto, setMotto] = useState(person?.motto ?? "");
  const [published, setPublished] = useState(person?.motto ?? "");
  const [mottoNotice, setMottoNotice] = useState(false);
  const [health, setHealth] = useState(person?.health ?? "");
  const [hobbies, setHobbies] = useState(person?.hobbies ?? []);
  useEffect(() => {
    setMotto(person?.motto ?? "");
    setPublished(person?.motto ?? "");
    setHealth(person?.health ?? "");
    setHobbies(person?.hobbies ?? []);
  }, [person]);

  const moods = useQuery({ queryKey: ["moods"], queryFn: accountService.moods, staleTime: Infinity });
  const children = useQuery({ queryKey: ["children", userId], queryFn: () => accountService.children(userId), enabled: showsChildren(type) });
  const [mood, setMood] = useState(person?.mood ?? "default");
  useEffect(() => setMood(person?.mood ?? "default"), [person?.mood]);

  const saveHobbies = async (next: typeof hobbies) => {
    setHobbies(next);
    await accountService.saveUserbook(userId, { hobbies: next.map((h) => ({ ...h, values: h.values ?? "" })) });
  };

  return (
    <div data-account-userbook>
      {mottoAllowed && (
        <section className="mt-24" data-account-motto-edit>
          <h2 className="h4">{t("userBook.profile.motto")}</h2>
          <div className="d-flex gap-8">
            <input
              className="form-control"
              maxLength={MOTTO_MAX}
              value={motto}
              onChange={(e) => {
                setMotto(e.target.value);
                setMottoNotice(false);
              }}
              data-motto-input
            />
            <Button
              type="button"
              color="primary"
              disabled={!mottoChanged(motto, published)}
              onClick={async () => {
                await accountService.saveUserbook(userId, { motto });
                setPublished(motto);
                setMottoNotice(true);
                void refresh();
              }}
              data-motto-publish
            >
              {t("portal.publish", { defaultValue: "Publier" })}
            </Button>
          </div>
          {mottoChanged(motto, published) && <small className="text-muted d-block mt-4">{t("userBook.motto.warning")}</small>}
          {mottoNotice && (
            <small className="text-success d-block mt-4" role="status" data-motto-done>
              {t("userBook.motto.success")}
            </small>
          )}
          {(moods.data?.length ?? 0) > 1 && (
            <label className="d-block mt-16">
              {t("userBook.profile.mood")}
              <select
                className="form-select"
                value={mood}
                onChange={async (e) => {
                  setMood(e.target.value);
                  await accountService.saveUserbook(userId, { mood: e.target.value });
                }}
                data-mood-select
              >
                {(moods.data ?? []).map((m) => (
                  <option key={m} value={m}>
                    {t(`userBook.mood.${m}`)}
                  </option>
                ))}
              </select>
            </label>
          )}
        </section>
      )}

      {showsChildren(type) && (children.data?.length ?? 0) > 0 && (
        <section className="mt-24" data-account-children>
          <h2 className="h4">{t("userBook.profile.children")}</h2>
          {children.data?.map((s) => (
            <div key={s.structureName} className="mb-16">
              <strong>{s.structureName}</strong>
              <ul className="list-unstyled mt-8">
                {s.children
                  .slice()
                  .sort((a, b) => a.displayName.localeCompare(b.displayName, "fr"))
                  .map((c) => (
                    <li key={c.id} className="d-flex align-items-center gap-8 mb-8">
                      <img src={`/userbook/avatar/${c.id}?thumbnail=100x100`} alt="" width={32} height={32} style={{ borderRadius: "50%" }} />
                      <span>{c.displayName}</span>
                      {c.classesNames?.[0] && <small className="text-muted">{c.classesNames[0]}</small>}
                    </li>
                  ))}
              </ul>
            </div>
          ))}
        </section>
      )}

      {showsRelatives(type) && person?.relatedName && (
        <section className="mt-24" data-account-relatives>
          <h2 className="h4">{t("userBook.profile.relatives")}</h2>
          <div className="d-flex align-items-center gap-8">
            {person.relatedId && <img src={`/userbook/avatar/${person.relatedId}?thumbnail=100x100`} alt="" width={32} height={32} style={{ borderRadius: "50%" }} />}
            <span>{person.relatedName}</span>
          </div>
        </section>
      )}

      {hobbies.length > 0 && (
        <section className="mt-24" data-account-hobbies>
          <h2 className="h4">{t("userBook.interests")}</h2>
          {hobbies
            .slice()
            .sort((a, b) => a.category.localeCompare(b.category))
            .map((h) => (
              <div key={h.category} className="d-flex align-items-center gap-16 mb-8" data-hobby={h.category}>
                <span style={{ width: 160 }}>{t(`userBook.hobby.${h.category}`)}</span>
                <input
                  className="form-control"
                  value={h.values ?? ""}
                  onChange={(e) => setHobbies(hobbies.map((x) => (x.category === h.category ? { ...x, values: e.target.value } : x)))}
                  onBlur={() => saveHobbies(hobbies)}
                  data-hobby-input={h.category}
                />
                <VisibilityToggle
                  id={`hobby-${h.category}`}
                  visible={hobbyIsPublic(h.visibility)}
                  onToggle={async () => {
                    const value = toggledHobbyVisibility(h.visibility);
                    setHobbies(hobbies.map((x) => (x.category === h.category ? { ...x, visibility: value } : x)));
                    await accountService.setHobbyVisibility(h.category, value);
                  }}
                />
              </div>
            ))}
        </section>
      )}

      <section className="mt-24" data-account-health>
        <h2 className="h4 d-flex align-items-center">
          {t("userBook.profile.health")}
          <VisibilityToggle
            id="health"
            visible={isVisible(person, "health")}
            onToggle={async () => {
              await accountService.setInfoVisibility("health", nextVisibility(isVisible(person, "health")));
              void refresh();
            }}
          />
        </h2>
        <textarea
          className="form-control"
          rows={3}
          value={health}
          onChange={(e) => setHealth(e.target.value)}
          onBlur={async () => {
            await accountService.saveUserbook(userId, { health });
          }}
          data-health-input
        />
      </section>
    </div>
  );
};
