import { useTranslation } from "react-i18next";

/** Workflows qui commandent les onglets de Mon compte. */
export const WF = {
  EXTERNAL_NOTIFICATIONS:
    "org.entcore.timeline.controllers.TimelineController|mixinConfig",
  HISTORY: "org.entcore.timeline.controllers.TimelineController|historyView",
  SWITCH_THEME:
    "org.entcore.directory.controllers.UserBookController|userBookSwitchTheme",
} as const;

export const workflowsOf = (user: unknown): Set<string> =>
  new Set(
    (
      (user as { authorizedActions?: Array<{ name: string }> } | undefined)
        ?.authorizedActions ?? []
    ).map((a) => a.name),
  );

/** Onglets de Mon compte (les mêmes que dans la page « Mon compte ») ; l'onglet courant est mis en avant. */
export const AccountTabs = ({
  current,
  workflows,
  hasEmail,
}: {
  current: "notifications" | "history";
  workflows: Set<string>;
  hasEmail: boolean;
}) => {
  const { t } = useTranslation("timeline");
  const tabs = [
    {
      id: "profile",
      label: "panel.my.profile",
      href: "/userbook/mon-compte?ui=react#/edit-me",
      show: true,
    },
    {
      id: "notifications",
      label: "panel.notifications.set",
      href: "/timeline/preferencesView?ui=react",
      show: hasEmail && workflows.has(WF.EXTERNAL_NOTIFICATIONS),
    },
    {
      id: "history",
      label: "panel.user.history",
      href: "/timeline/historyView?ui=react",
      show: workflows.has(WF.HISTORY),
    },
    {
      id: "theme",
      label: "panel.user.pickTheme",
      href: "/userbook/mon-compte?ui=react#/themes",
      show: workflows.has(WF.SWITCH_THEME),
    },
  ].filter((x) => x.show || x.id === current);
  return (
    <nav
      role="tablist"
      className="d-flex gap-16 mb-24"
      style={{ borderBottom: "1px solid #dee2e6" }}
      data-account-tabs
    >
      {tabs.map((tab) => (
        <a
          key={tab.id}
          role="tab"
          aria-selected={tab.id === current}
          href={tab.href}
          className="btn btn-link"
          style={{
            borderBottom:
              tab.id === current
                ? "3px solid #ffb400"
                : "3px solid transparent",
            fontWeight: tab.id === current ? 700 : 400,
            borderRadius: 0,
            color: "inherit",
            textDecoration: "none",
          }}
          data-account-tab={tab.id}
        >
          {t(tab.label)}
        </a>
      ))}
    </nav>
  );
};
