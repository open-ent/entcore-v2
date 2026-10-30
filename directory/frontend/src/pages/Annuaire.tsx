import {
  AppHeader,
  Button,
  Layout,
  LoadingScreen,
  useEdificeClient,
} from "@open-ent/react";
import { useQuery } from "@tanstack/react-query";
import { FormEvent, useState } from "react";
import { useTranslation } from "react-i18next";
import { DiscoverPanel } from "../components/DiscoverPanel";
import { discoverAllowed } from "../lib/discover";
import { FavoritesPanel } from "../components/FavoritesPanel";
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
} from "../lib/directory";
import { WF, workflowsOf } from "../lib/workflows";
import { directoryService } from "../services/account.service";

/** Annuaire : recherche des personnes visibles par nom, profil, établissement et classe, puis fiche d'une personne. */
export const Annuaire = () => {
  const { t } = useTranslation("directory");
  const [text, setText] = useState("");
  const [filters, setFilters] = useState<Filters>({});
  const [submitted, setSubmitted] = useState<{
    text: string;
    filters: Filters;
  } | null>(null);
  const [selected, setSelected] = useState<Visible | null>(null);
  const [mode, setMode] = useState<
    "users" | "groups" | "favorites" | "discover"
  >("users");
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

  return (
    <Layout>
      <AppHeader>
        <h1 className="h2" data-annuaire-title>
          {t("userBook.directory.title")}
        </h1>
      </AppHeader>
      {selected ? (
        <UserCard user={selected} onBack={() => setSelected(null)} />
      ) : group ? (
        <GroupCard
          group={group}
          onBack={() => setGroup(null)}
          onOpenUser={setSelected}
        />
      ) : (
        <>
          <div
            role="tablist"
            className="d-flex gap-16 mb-16"
            style={{ borderBottom: "1px solid #dee2e6" }}
            data-directory-modes
          >
            {(["users", "groups", "favorites", "discover"] as const)
              .filter(
                (m) =>
                  (m !== "favorites" || favoritesAllowed) &&
                  (m !== "discover" || discoverOk),
              )
              .map((m) => (
                <button
                  key={m}
                  type="button"
                  role="tab"
                  aria-selected={mode === m}
                  className="btn btn-link"
                  style={{
                    borderBottom:
                      mode === m
                        ? "3px solid #ffb400"
                        : "3px solid transparent",
                    fontWeight: mode === m ? 700 : 400,
                    borderRadius: 0,
                    color: "inherit",
                  }}
                  onClick={() => {
                    setMode(m);
                    setSubmitted(null);
                  }}
                  data-directory-mode={m}
                >
                  {t(
                    m === "users"
                      ? "share.users"
                      : m === "groups"
                        ? "share.groups"
                        : m === "favorites"
                          ? "directory.favorites"
                          : "userbook.discover.visible",
                  )}
                </button>
              ))}
          </div>
          {mode === "discover" ? (
            <DiscoverPanel
              myId={(me as { userId?: string } | undefined)?.userId ?? ""}
            />
          ) : mode === "favorites" ? (
            <FavoritesPanel />
          ) : (
            <>
              <form onSubmit={submit} className="mb-24" data-directory-form>
                <div className="d-flex gap-8 mb-16">
                  <input
                    className="form-control"
                    value={text}
                    onChange={(e) => setText(e.target.value)}
                    placeholder={t("userBook.search")}
                    aria-label={t("userBook.search")}
                    data-directory-search
                  />
                  <Button type="submit" color="primary" data-directory-submit>
                    {t("directory.search")}
                  </Button>
                </div>
                {mode === "users" && (
                  <fieldset className="mb-16">
                    <legend className="h6">{t("directory.profiles")}</legend>
                    <div className="d-flex gap-16 flex-wrap">
                      {PROFILES.map((p) => (
                        <label key={p}>
                          <input
                            type="checkbox"
                            checked={filters.profiles?.includes(p) ?? false}
                            onChange={() =>
                              setFilters({
                                ...filters,
                                profiles: toggleValue(filters.profiles, p),
                              })
                            }
                            data-filter-profile={p}
                          />{" "}
                          {t(`directory.${p}`)}
                        </label>
                      ))}
                    </div>
                  </fieldset>
                )}
                {(criteria.data?.structures?.length ?? 0) > 0 && (
                  <div className="d-flex gap-16 flex-wrap">
                    <label>
                      {t("directory.search.structure")}
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
                        <option value="">—</option>
                        {criteria.data?.structures.map((s) => (
                          <option key={s.id} value={s.id}>
                            {s.name}
                          </option>
                        ))}
                      </select>
                    </label>
                    {filters.structures?.[0] && (
                      <label>
                        {t("directory.classe")}
                        <select
                          className="form-select"
                          value={filters.classes?.[0] ?? ""}
                          onChange={(e) =>
                            setFilters({
                              ...filters,
                              classes: e.target.value ? [e.target.value] : [],
                            })
                          }
                          data-filter-class
                        >
                          <option value="">—</option>
                          {(classes.data ?? []).map((c) => (
                            <option key={c.id} value={c.id}>
                              {c.label}
                            </option>
                          ))}
                        </select>
                      </label>
                    )}
                  </div>
                )}
              </form>
              {(results.isLoading || groupResults.isLoading) && (
                <LoadingScreen position={false} />
              )}
              {results.isError && (
                <div
                  className="alert alert-danger"
                  role="alert"
                  data-directory-error
                >
                  {t("account.error", {
                    defaultValue: "La recherche a échoué.",
                  })}
                </div>
              )}
              {!submitted && (
                <div
                  className="text-center text-muted"
                  data-directory-empty-start
                >
                  <h2 className="h4">
                    {t("userBook.directory.emptyScreen.users.header")}
                  </h2>
                  <p>{t("userBook.directory.emptyScreen.users.footer")}</p>
                </div>
              )}
              {mode === "groups" &&
                groupResults.isSuccess &&
                groupResults.data.length === 0 && (
                  <p data-directory-noresult>{t("noresult")}</p>
                )}
              {mode === "groups" &&
                groupResults.isSuccess &&
                groupResults.data.length > 0 && (
                  <ul
                    className="list-unstyled d-flex flex-column gap-8"
                    data-directory-group-results
                  >
                    {groupResults.data.map((g) => (
                      <li key={g.id}>
                        <button
                          type="button"
                          className="btn btn-outline-secondary w-100 text-start"
                          onClick={() => setGroup(g)}
                          data-directory-group={g.id}
                        >
                          {g.name}
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              {mode === "users" &&
                results.isSuccess &&
                results.data.length === 0 && (
                  <p data-directory-noresult>{t("noresult")}</p>
                )}
              {mode === "users" &&
                results.isSuccess &&
                results.data.length > 0 && (
                  <ul
                    className="list-unstyled d-flex flex-wrap gap-16"
                    data-directory-results
                  >
                    {results.data.map((u) => (
                      <li key={u.id} style={{ width: 240 }}>
                        <button
                          type="button"
                          className="btn btn-outline-secondary w-100 d-flex gap-8 align-items-center text-start"
                          onClick={() => setSelected(u)}
                          data-directory-user={u.id}
                        >
                          <img
                            src={`/userbook/avatar/${u.id}?thumbnail=100x100`}
                            alt=""
                            width={40}
                            height={40}
                            style={{ borderRadius: "50%", objectFit: "cover" }}
                            loading="lazy"
                          />
                          <span>
                            <strong className="d-block">{u.displayName}</strong>
                            {u.profile && (
                              <small className="text-muted">
                                {t(u.profile)}
                              </small>
                            )}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
            </>
          )}
        </>
      )}
    </Layout>
  );
};
