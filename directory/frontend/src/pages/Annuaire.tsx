import {
  AppHeader,
  Button,
  Layout,
  LoadingScreen,
  useEdificeClient,
} from "@open-ent/react";
import { useQuery } from "@tanstack/react-query";
import { FormEvent, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { DiscoverPanel } from "../components/DiscoverPanel";
import { discoverAllowed } from "../lib/discover";
import { FavoritesPanel } from "../components/FavoritesPanel";
import { AddressBook, GroupMark, PersonEntry } from "../components/AddressBook";
import { ANNUAIRE_CSS } from "../components/annuaireStyles";
import { GroupCard } from "../components/GroupCard";
import { UserCard } from "../components/UserCard";
import {
  Filters,
  groupSearchBody,
  PROFILES,
  searchBody,
  sortGroups,
  sortVisibles,
  toggleValue,
  Visible,
  VisibleGroup,
  userFromHash,
} from "../lib/directory";
import { WF, workflowsOf } from "../lib/workflows";
import { directoryService } from "../services/account.service";

const MODES = ["users", "groups", "favorites", "discover"] as const;
type Mode = (typeof MODES)[number];

/** Annuaire : recherche des personnes visibles par nom, profil, établissement et classe, puis fiche d'une personne. */
export const Annuaire = () => {
  const { t } = useTranslation("directory");
  const [text, setText] = useState("");
  const [filters, setFilters] = useState<Filters>({});
  const [submitted, setSubmitted] = useState<{
    text: string;
    filters: Filters;
  } | null>(null);
  // un lien `/userbook/annuaire#<id>#<Profil>` (notifications, ancienne interface) ouvre directement la fiche
  const [selected, setSelected] = useState<Visible | null>(() => userFromHash(window.location.hash));
  useEffect(() => {
    const onHash = () => {
      const u = userFromHash(window.location.hash);
      if (u) setSelected(u);
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  const closeCard = () => {
    setSelected(null);
    // l'ancre rouvrirait la fiche au prochain rechargement
    if (userFromHash(window.location.hash))
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
  };
  const [mode, setMode] = useState<Mode>("users");
  const { user: me } = useEdificeClient();
  const accepted = useQuery({
    queryKey: ["discover-profiles"],
    queryFn: directoryService.discoverProfiles,
    staleTime: 1000 * 60 * 10,
  });
  const discoverOk = discoverAllowed(
    (me as { type?: string } | undefined)?.type,
    accepted.data ?? [],
  );
  const favoritesAllowed = workflowsOf(me).has(WF.SHARE_BOOKMARKS);
  const [group, setGroup] = useState<VisibleGroup | null>(null);

  const criteria = useQuery({
    queryKey: ["criteria"],
    queryFn: directoryService.criteria,
    staleTime: 1000 * 60 * 10,
  });
  const results = useQuery({
    queryKey: ["directory-search", mode, submitted],
    enabled: !!submitted && mode === "users",
    queryFn: async () =>
      sortVisibles(
        await directoryService.search(
          searchBody(submitted?.text ?? "", submitted?.filters ?? {}),
        ),
      ),
  });
  const groupResults = useQuery({
    queryKey: ["directory-search", mode, submitted],
    enabled: !!submitted && mode === "groups",
    queryFn: async () =>
      sortGroups(
        await directoryService.searchGroups(
          groupSearchBody(submitted?.text ?? "", submitted?.filters ?? {}),
        ),
      ),
  });
  const classes = useQuery({
    queryKey: ["classes", filters.structures?.[0]],
    enabled: !!filters.structures?.[0],
    queryFn: () =>
      directoryService.classesOf(filters.structures?.[0] as string),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setSelected(null);
    setGroup(null);
    setSubmitted({ text, filters });
  };

  const tabLabel = (m: Mode) =>
    t(
      m === "users"
        ? "share.users"
        : m === "groups"
          ? "share.groups"
          : m === "favorites"
            ? "directory.favorites"
            : "userbook.discover.visible",
    );

  return (
    <Layout>
      <style>{ANNUAIRE_CSS}</style>
      <AppHeader>
        <h1 className="h2" data-annuaire-title>
          {t("userBook.directory.title")}
        </h1>
      </AppHeader>
      <div className="annuaire">
        {selected ? (
          <UserCard user={selected} onBack={closeCard} />
        ) : group ? (
          <GroupCard group={group} onBack={() => setGroup(null)} onOpenUser={setSelected} />
        ) : (
          <>
            <div role="tablist" className="ann-tabs" data-directory-modes>
              {MODES.filter(
                (m) => (m !== "favorites" || favoritesAllowed) && (m !== "discover" || discoverOk),
              ).map((m) => (
                <button
                  key={m}
                  type="button"
                  role="tab"
                  aria-selected={mode === m}
                  className="ann-tab"
                  onClick={() => {
                    setMode(m);
                    setSubmitted(null);
                  }}
                  data-directory-mode={m}
                >
                  {tabLabel(m)}
                </button>
              ))}
            </div>
            {mode === "discover" ? (
              <DiscoverPanel myId={(me as { userId?: string } | undefined)?.userId ?? ""} />
            ) : mode === "favorites" ? (
              <FavoritesPanel />
            ) : (
              <>
                <form onSubmit={submit} className="ann-search" role="search" data-directory-form>
                  <div className="ann-query">
                    <div className="ann-query-field">
                      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
                        <circle cx="11" cy="11" r="7" />
                        <path d="m20 20-3.5-3.5" />
                      </svg>
                      <input
                        type="search"
                        value={text}
                        onChange={(e) => setText(e.target.value)}
                        placeholder={t(mode === "groups" ? "userBook.search.name" : "userBook.search")}
                        aria-label={t("userBook.search")}
                        data-directory-search
                      />
                    </div>
                    <Button type="submit" color="primary" data-directory-submit>
                      {t("directory.search")}
                    </Button>
                  </div>
                  <div className="ann-filters">
                    {mode === "users" && (
                      <fieldset>
                        <legend className="ann-filter-label">{t("directory.profiles")}</legend>
                        <div className="ann-chips">
                          {PROFILES.map((p) => (
                            <label key={p} className="ann-chip" data-profile={p}>
                              <input
                                type="checkbox"
                                checked={filters.profiles?.includes(p) ?? false}
                                onChange={() =>
                                  setFilters({ ...filters, profiles: toggleValue(filters.profiles, p) })
                                }
                                data-filter-profile={p}
                              />
                              {t(`directory.${p}`)}
                            </label>
                          ))}
                        </div>
                      </fieldset>
                    )}
                    {(criteria.data?.structures?.length ?? 0) > 0 && (
                      <label className="ann-select">
                        <span className="ann-filter-label">{t("directory.search.structure")}</span>
                        <select
                          className="form-select"
                          value={filters.structures?.[0] ?? ""}
                          onChange={(e) =>
                            setFilters({
                              ...filters,
                              structures: e.target.value ? [e.target.value] : [],
                              classes: [],
                            })
                          }
                          data-filter-structure
                        >
                          <option value="">{t("directory.allStructures")}</option>
                          {criteria.data?.structures.map((s) => (
                            <option key={s.id} value={s.id}>
                              {s.name}
                            </option>
                          ))}
                        </select>
                      </label>
                    )}
                    {filters.structures?.[0] && (
                      <label className="ann-select">
                        <span className="ann-filter-label">{t("directory.classe")}</span>
                        <select
                          className="form-select"
                          value={filters.classes?.[0] ?? ""}
                          onChange={(e) =>
                            setFilters({ ...filters, classes: e.target.value ? [e.target.value] : [] })
                          }
                          data-filter-class
                        >
                          <option value="">{t("directory.allClasses")}</option>
                          {(classes.data ?? []).map((c) => (
                            <option key={c.id} value={c.id}>
                              {c.label}
                            </option>
                          ))}
                        </select>
                      </label>
                    )}
                  </div>
                </form>
                {(results.isLoading || groupResults.isLoading) && <LoadingScreen position={false} />}
                {(results.isError || groupResults.isError) && (
                  <div className="alert alert-danger" role="alert" data-directory-error>
                    {t("account.error", { defaultValue: "La recherche a échoué." })}
                  </div>
                )}
                {!submitted && (
                  <div className="ann-empty" data-directory-empty-start>
                    <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M5 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5z" />
                      <path d="M5 3v18M3 7h4M3 12h4M3 17h4" />
                      <circle cx="12.5" cy="10" r="2.5" />
                      <path d="M8.5 16.5c.6-1.9 2.1-3 4-3s3.4 1.1 4 3" />
                    </svg>
                    <h2>{t("userBook.directory.emptyScreen.users.header")}</h2>
                    <p>{t("userBook.directory.emptyScreen.users.footer")}</p>
                  </div>
                )}
                {mode === "groups" && groupResults.isSuccess && groupResults.data.length === 0 && (
                  <p className="ann-empty" data-directory-noresult>{t("noresult")}</p>
                )}
                {mode === "groups" && groupResults.isSuccess && groupResults.data.length > 0 && (
                  <AddressBook
                    items={groupResults.data}
                    nameOf={(g) => g.name}
                    idPrefix="ann-groups"
                    count={t("directory.count.groups", { count: groupResults.data.length })}
                    data-directory-group-results=""
                    renderItem={(g) => (
                      <button type="button" className="ann-entry" onClick={() => setGroup(g)} data-directory-group={g.id}>
                        <GroupMark />
                        <span className="ann-entry-text">
                          <strong title={g.name}>{g.name}</strong>
                        </span>
                      </button>
                    )}
                  />
                )}
                {mode === "users" && results.isSuccess && results.data.length === 0 && (
                  <p className="ann-empty" data-directory-noresult>{t("noresult")}</p>
                )}
                {mode === "users" && results.isSuccess && results.data.length > 0 && (
                  <AddressBook
                    items={results.data}
                    nameOf={(u) => u.displayName}
                    idPrefix="ann-people"
                    count={t("directory.count.people", { count: results.data.length })}
                    data-directory-results=""
                    renderItem={(u) => <PersonEntry user={u} onOpen={setSelected} data-directory-user={u.id} />}
                  />
                )}
              </>
            )}
          </>
        )}
      </div>
    </Layout>
  );
};
