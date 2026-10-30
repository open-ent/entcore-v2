import {
  AppHeader,
  Button,
  Layout,
  LoadingScreen,
  useEdificeClient,
} from "@open-ent/react";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { AccountTabs, workflowsOf } from "../components/AccountTabs";
import {
  AppGroup,
  commonFrequency,
  Frequency,
  FREQUENCIES,
  groupByApp,
  preferenceBody,
  setActionFrequency,
  setGroupFrequency,
  withPreferences,
} from "../lib/notifs";
import { timelineService } from "../services/timeline.service";

const LABEL: Record<Frequency, string> = {
  IMMEDIATE: "xtnotifications.immediat",
  DAILY: "xtnotifications.daily",
  WEEKLY: "xtnotifications.weekly",
  NEVER: "xtnotifications.never",
};

/** Notifications externes : pour chaque application, et chaque type de notification, à quelle fréquence recevoir un courriel. */
export const Preferences = () => {
  const { t, i18n } = useTranslation("timeline");
  const { user } = useEdificeClient();
  const apps = (
    (user as { apps?: Array<{ name: string }> } | undefined)?.apps ?? []
  ).map((a) => a.name);
  const defaults = useQuery({
    queryKey: ["tl-defaults"],
    queryFn: timelineService.defaults,
  });
  const preference = useQuery({
    queryKey: ["tl-preference"],
    queryFn: timelineService.preference,
  });
  const person = useQuery({
    queryKey: ["tl-person"],
    queryFn: timelineService.person,
  });
  const quiet = useQuery({
    queryKey: ["tl-quiet"],
    queryFn: timelineService.quietHours,
  });
  const labels = useQuery({
    queryKey: ["tl-labels"],
    queryFn: timelineService.notificationLabels,
    staleTime: Infinity,
  });

  const [groups, setGroups] = useState<AppGroup[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (defaults.data && preference.data !== undefined) {
      const g = withPreferences(
        groupByApp(defaults.data, apps),
        preference.data,
      );
      setGroups(g);
      setOpen((cur) => cur ?? g[0]?.type ?? null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defaults.data, preference.data, apps.join("|")]);

  const label = (key: string) =>
    labels.data?.[key] ?? labels.data?.[key.toLowerCase()] ?? t(key);
  const sorted = (groups ?? []).slice().sort((a, b) =>
    t(a.type.toLowerCase()).localeCompare(t(b.type.toLowerCase()), "fr", {
      sensitivity: "base",
    }),
  );
  void i18n;

  if (defaults.isError || preference.isError)
    return (
      <Layout>
        <AppHeader>
          <h1 className="h2" data-prefs-title>
            {t("account.myaccount")}
          </h1>
        </AppHeader>
        <div className="alert alert-warning" role="alert" data-page-forbidden>
          {t("account.page.forbidden", { defaultValue: "Vous n'avez pas accès à cette page." })}
        </div>
      </Layout>
    );
  if (defaults.isLoading || preference.isLoading || !groups)
    return <LoadingScreen position={false} />;

  const save = async () => {
    setFailed(false);
    try {
      await timelineService.savePreference(
        preferenceBody(preference.data ?? null, groups),
      );
      setSaved(true);
    } catch {
      setFailed(true);
    }
  };

  const hasEmail = !!person.data?.email;
  const structureQuiet =
    quiet.data?.enabled &&
    (quiet.data.managedBy ?? "").toUpperCase() === "STRUCTURE";

  return (
    <Layout>
      <AppHeader>
        <h1 className="h2" data-prefs-title>
          {t("account.myaccount")}
        </h1>
      </AppHeader>
      <AccountTabs
        current="notifications"
        workflows={workflowsOf(user)}
        hasEmail={hasEmail}
      />
      {structureQuiet && (
        <div className="alert alert-info" role="note" data-prefs-quiet>
          <strong className="d-block">
            {t("infotip.quiethours.structure.title")}
          </strong>
          {t("infotip.quiethours.structure.text")}
        </div>
      )}
      <div className="d-flex align-items-center justify-content-between flex-wrap gap-16 mb-16">
        <div>
          {t("xtnotifications.mail")}{" "}
          <strong data-prefs-email>{person.data?.email}</strong>
        </div>
        <Button type="button" color="primary" onClick={save} data-prefs-save>
          {t("xtnotifications.validate")}
        </Button>
      </div>
      {saved && (
        <div className="alert alert-success" role="status" data-prefs-saved>
          {t("preferences.saved")}
        </div>
      )}
      {failed && (
        <div className="alert alert-danger" role="alert" data-prefs-failed>
          {t("account.error.save", {
            defaultValue: "L'enregistrement a échoué.",
          })}
        </div>
      )}
      <table className="table" data-prefs-table>
        <thead>
          <tr>
            <th />
            {FREQUENCIES.map((f) => (
              <th key={f} scope="col" className="text-center">
                {t(LABEL[f])}
              </th>
            ))}
          </tr>
        </thead>
        {sorted.map((g) => (
          <tbody key={g.type} data-prefs-app={g.type}>
            <tr className="table-light">
              <th scope="row">
                <button
                  type="button"
                  className="btn btn-link p-0"
                  aria-expanded={open === g.type}
                  onClick={() => setOpen(open === g.type ? null : g.type)}
                  data-prefs-toggle={g.type}
                >
                  {open === g.type ? "▾" : "▸"} {t(g.type.toLowerCase())}
                </button>
              </th>
              {FREQUENCIES.map((f) => (
                <td key={f} className="text-center">
                  <input
                    type="radio"
                    name={`app-${g.type}`}
                    aria-label={`${t(g.type.toLowerCase())} — ${t(LABEL[f])}`}
                    checked={commonFrequency(g) === f}
                    onChange={() => {
                      setSaved(false);
                      setGroups(setGroupFrequency(groups, g.type, f));
                    }}
                    data-prefs-group={`${g.type}:${f}`}
                  />
                </td>
              ))}
            </tr>
            {open === g.type &&
              g.actions
                .slice()
                .sort((a, b) =>
                  label(a.key).localeCompare(label(b.key), "fr", {
                    sensitivity: "base",
                  }),
                )
                .map((a) => (
                  <tr key={a.key} data-prefs-action={a.key}>
                    <td>{label(a.key)}</td>
                    {FREQUENCIES.map((f) => (
                      <td key={f} className="text-center">
                        <input
                          type="radio"
                          name={a.key}
                          aria-label={`${label(a.key)} — ${t(LABEL[f])}`}
                          checked={a.defaultFrequency === f}
                          onChange={() => {
                            setSaved(false);
                            setGroups(setActionFrequency(groups, a.key, f));
                          }}
                          data-prefs-radio={`${a.key}:${f}`}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
          </tbody>
        ))}
      </table>
    </Layout>
  );
};
