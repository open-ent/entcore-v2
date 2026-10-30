import {
  AppHeader,
  Button,
  Layout,
  LoadingScreen,
  useEdificeClient,
} from "@open-ent/react";
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { AccountTabs, workflowsOf } from "../components/AccountTabs";
import {
  accessibleTypes,
  mergePage,
  Notification,
  notificationsQuery,
  sanitizeMessage,
  toggleAll,
  toggleType,
} from "../lib/history";
import { timelineService } from "../services/timeline.service";

const DELETE_OWN =
  "org.entcore.timeline.controllers.TimelineController|deleteNotification";

const formatDate = (d: { $date: number | string }) =>
  new Date(d.$date).toLocaleString("fr-FR", {
    dateStyle: "short",
    timeStyle: "short",
  });

/** Historique des notifications de l'utilisateur : filtres par application, défilement paginé, suppression d'une notification. */
export const History = () => {
  const { t } = useTranslation("timeline");
  const { user } = useEdificeClient();
  const qc = useQueryClient();
  const apps = (
    (user as { apps?: Array<{ name: string }> } | undefined)?.apps ?? []
  ).map((a) => a.name);
  const types = useQuery({
    queryKey: ["tl-types"],
    queryFn: timelineService.types,
  });
  const registered = useQuery({
    queryKey: ["tl-registered"],
    queryFn: timelineService.registered,
  });
  const person = useQuery({
    queryKey: ["tl-person"],
    queryFn: timelineService.person,
  });

  const available = useMemo(
    () => accessibleTypes(types.data ?? [], registered.data ?? [], apps),
    [types.data, registered.data, apps.join("|")],
  ); // eslint-disable-line react-hooks/exhaustive-deps
  const [selected, setSelected] = useState<string[] | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [removed, setRemoved] = useState<string[]>([]);
  useEffect(() => {
    // les applications de l'utilisateur arrivent avec sa session : on attend d'avoir des types accessibles avant d'initialiser les filtres
    if (selected === null && available.length > 0) setSelected(available);
  }, [available, selected]);

  const sel = selected ?? [];
  const list = useInfiniteQuery({
    queryKey: ["tl-notifications", [...sel].sort().join(",")],
    enabled: selected !== null && sel.length > 0,
    initialPageParam: 0,
    queryFn: async ({ pageParam }) =>
      (await timelineService.notifications(notificationsQuery(sel, pageParam)))
        .results ?? [],
    getNextPageParam: (last, _all, lastParam) =>
      last.length > 0 ? lastParam + 1 : undefined,
  });
  const notifications: Notification[] = (list.data?.pages ?? [])
    .reduce<Notification[]>((acc, page) => mergePage(acc, page), [])
    .filter((n) => !removed.includes(n._id));

  // défilement : la page suivante se charge quand le bas de la liste devient visible
  const sentinel = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !list.hasNextPage) return;
    const obs = new IntersectionObserver((entries) => {
      if (entries[0].isIntersecting && !list.isFetchingNextPage)
        void list.fetchNextPage();
    });
    obs.observe(el);
    return () => obs.disconnect();
  }, [
    list.hasNextPage,
    list.isFetchingNextPage,
    list.fetchNextPage,
    notifications.length,
  ]); // eslint-disable-line react-hooks/exhaustive-deps

  const workflows = workflowsOf(user);
  const canDelete = workflows.has(DELETE_OWN);

  if (types.isError || registered.isError)
    return (
      <Layout>
        <AppHeader>
          <h1 className="h2" data-history-title>
            {t("account.myaccount")}
          </h1>
        </AppHeader>
        <div className="alert alert-warning" role="alert" data-page-forbidden>
          {t("account.page.forbidden", { defaultValue: "Vous n'avez pas accès à cette page." })}
        </div>
      </Layout>
    );
  if (types.isLoading || registered.isLoading)
    return <LoadingScreen position={false} />;
  const inactive = available.length - sel.length;

  return (
    <Layout>
      <AppHeader>
        <h1 className="h2" data-history-title>
          {t("account.myaccount")}
        </h1>
      </AppHeader>
      <AccountTabs
        current="history"
        workflows={workflows}
        hasEmail={!!person.data?.email}
      />

      {available.length > 0 && (
        <section className="mb-16" data-history-filters>
          <button
            type="button"
            className="btn btn-link p-0"
            aria-expanded={filtersOpen}
            onClick={() => setFiltersOpen(!filtersOpen)}
            data-history-filters-toggle
          >
            {filtersOpen ? "▾" : "▸"} {t("filters.history")}{" "}
            <small className="text-muted">
              ({t("filters.unactives")} {inactive})
            </small>
          </button>
          {filtersOpen && (
            <div className="d-flex flex-wrap gap-8 mt-8">
              <label
                className="badge bg-light text-dark"
                data-history-filter-all
              >
                <input
                  type="checkbox"
                  checked={sel.length === available.length}
                  onChange={() => setSelected(toggleAll(sel, available))}
                />{" "}
                {t("filters.all.none")}
              </label>
              {available.map((type) => (
                <label
                  key={type}
                  className="badge bg-light text-dark"
                  data-history-filter={type}
                >
                  <input
                    type="checkbox"
                    checked={sel.includes(type)}
                    onChange={() => setSelected(toggleType(sel, type))}
                  />{" "}
                  {t(type.toLowerCase())}
                </label>
              ))}
            </div>
          )}
        </section>
      )}

      {selected !== null &&
        sel.length < available.length &&
        notifications.length === 0 &&
        !list.isLoading && (
          <p className="alert alert-info" data-history-unselected>
            {t("filters.history.unselected")}
          </p>
        )}
      {list.isSuccess &&
        sel.length === available.length &&
        notifications.length === 0 &&
        !list.hasNextPage && (
          <p className="alert alert-info" data-history-empty>
            {t("timeline.history.empty")}
          </p>
        )}

      <ul className="list-unstyled" data-history-list>
        {notifications.map((n) => (
          <li
            key={n._id}
            className="d-flex gap-16 align-items-start border-bottom py-12"
            data-notification={n._id}
            data-notification-type={n.type}
          >
            <img
              src={
                n.sender
                  ? `/userbook/avatar/${n.sender}?thumbnail=100x100`
                  : "/assets/themes/edifice-bootstrap/img/illustrations/system-avatar.svg"
              }
              alt=""
              width={40}
              height={40}
              style={{ borderRadius: "50%", objectFit: "cover" }}
              loading="lazy"
            />
            <div className="flex-grow-1">
              <div
                dangerouslySetInnerHTML={{ __html: sanitizeMessage(n.message) }}
              />
              <small className="text-muted">
                {t(n.type.toLowerCase())} — {formatDate(n.date)}
              </small>
            </div>
            {canDelete && (
              <Button
                type="button"
                color="tertiary"
                variant="ghost"
                onClick={async () => {
                  await timelineService.deleteNotification(n._id);
                  setRemoved((r) => [...r, n._id]);
                  void qc.invalidateQueries({ queryKey: ["tl-notifications"] });
                }}
                data-notification-delete={n._id}
              >
                {t("timeline.action.delete.own")}
              </Button>
            )}
          </li>
        ))}
      </ul>
      {(list.isFetching || list.isFetchingNextPage) && (
        <p className="text-muted" data-history-loading>
          {t("loading")}
        </p>
      )}
      <div ref={sentinel} style={{ height: 1 }} />
    </Layout>
  );
};
