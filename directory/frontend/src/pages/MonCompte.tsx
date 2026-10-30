import { AppHeader, Layout, LoadingScreen, useEdificeClient } from "@open-ent/react";
import { useTranslation } from "react-i18next";
import { classNames, profileRows } from "../lib/profile";
import { WF, workflowsOf } from "../lib/workflows";
import { useAccount } from "../services/queries";

/** Onglets de Mon compte : « Mon profil » est cette page ; les autres ouvrent les pages des modules concernés, selon les droits. */
const Tabs = ({ workflows, hasEmail }: { workflows: Set<string>; hasEmail: boolean }) => {
  const { t } = useTranslation("directory");
  const tabs = [
    { id: "profile", label: "panel.my.profile", href: "/userbook/mon-compte", current: true, show: true },
    { id: "notifications", label: "panel.notifications.set", href: "/timeline/preferencesView", show: hasEmail && workflows.has(WF.EXTERNAL_NOTIFICATIONS) },
    { id: "history", label: "panel.user.history", href: "/timeline/historyView", show: workflows.has(WF.HISTORY) },
    { id: "theme", label: "panel.user.pickTheme", href: "/userbook/mon-compte#/themes", show: workflows.has(WF.SWITCH_THEME) },
  ].filter((tab) => tab.show);
  return (
    <nav role="tablist" className="d-flex gap-16 mb-24" style={{ borderBottom: "1px solid #dee2e6" }} data-account-tabs>
      {tabs.map((tab) => (
        <a
          key={tab.id}
          role="tab"
          aria-selected={!!tab.current}
          href={tab.href}
          className="btn btn-link"
          style={{ borderBottom: tab.current ? "3px solid #ffb400" : "3px solid transparent", fontWeight: tab.current ? 700 : 400, borderRadius: 0, color: "inherit", textDecoration: "none" }}
          data-account-tab={tab.id}
        >
          {t(tab.label)}
        </a>
      ))}
    </nav>
  );
};

/** Mon compte : identité, rattachements, devise. La modification des informations arrive aux lots suivants. */
export const MonCompte = () => {
  const { t } = useTranslation("directory");
  const { user } = useEdificeClient();
  const account = useAccount();
  const workflows = workflowsOf(user);

  if (account.isLoading) return <LoadingScreen position={false} />;
  const person = account.data?.person;
  const infos = account.data?.infos;
  const rows = profileRows(infos, person);

  return (
    <Layout>
      <AppHeader>
        <h1 className="h2" data-account-title>
          {t("account.myaccount")}
        </h1>
      </AppHeader>
      {account.isError ? (
        <div className="alert alert-danger" role="alert" data-account-error>
          {t("account.error", { defaultValue: "Vos informations n'ont pas pu être chargées." })}
        </div>
      ) : (
        <>
          <Tabs workflows={workflows} hasEmail={!!infos?.email} />
          <section data-account-profile className="d-flex gap-24 flex-wrap">
            <img
              src={`/userbook/avatar/${account.data?.userId}?thumbnail=100x100`}
              alt=""
              width={96}
              height={96}
              style={{ borderRadius: "50%", objectFit: "contain", background: "#e9ecef" }}
              data-account-avatar
            />
            <dl className="mb-0" style={{ minWidth: 280 }}>
              {rows.map((row) => (
                <div key={row.key} className="d-flex gap-16 mb-8" data-account-row={row.key}>
                  <dt style={{ width: 200, fontWeight: 600 }}>{t(row.label)}</dt>
                  <dd className="mb-0">{row.value}</dd>
                </div>
              ))}
            </dl>
          </section>
          {(person?.schools?.length ?? 0) > 0 && (
            <section className="mt-24" data-account-schools>
              <h2 className="h4">{t("userBook.profile.schools")}</h2>
              <ul>
                {person?.schools?.map((school) => (
                  <li key={school.id}>
                    {school.name}
                    {classNames(school.classes).length > 0 && <> — {classNames(school.classes).join(", ")}</>}
                  </li>
                ))}
              </ul>
            </section>
          )}
          {person?.motto && (
            <section className="mt-24" data-account-motto>
              <h2 className="h4">{t("userBook.profile.motto")}</h2>
              <p>{person.motto}</p>
            </section>
          )}
        </>
      )}
    </Layout>
  );
};
