import { AppHeader, Button, Layout, LoadingScreen, useEdificeClient } from "@open-ent/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { EditFieldModal } from "../components/EditFieldModal";
import { OtpModal } from "../components/OtpModal";
import { UserbookPanel, VisibilityToggle } from "../components/UserbookPanel";
import { ThemePanel } from "../components/ThemePanel";
import { PasswordModal } from "../components/PasswordModal";
import { ValidationModal } from "../components/ValidationModal";
import { ROW_VISIBILITY, isVisible, nextVisibility } from "../lib/userbook";
import { apiErrorKind, classNames, isStudent, isValidPhone, loginError, profileRows } from "../lib/profile";
import { WF, workflowsOf } from "../lib/workflows";
import { accountService } from "../services/account.service";
import { useAccount } from "../services/queries";

type Editing = "displayName" | "tel" | "login" | null;

/** Message d'une erreur de l'API (`{error: "…"}` dans la réponse). */
const messageOf = (e: unknown): string | undefined => (e as { message?: string; error?: string } | undefined)?.error ?? (e as Error | undefined)?.message;

/** Onglets de Mon compte : « Mon profil » est cette page ; les autres ouvrent les pages des modules concernés, selon les droits. */
const Tabs = ({ workflows, hasEmail, themes }: { workflows: Set<string>; hasEmail: boolean; themes: boolean }) => {
  const { t } = useTranslation("directory");
  const tabs = [
    { id: "profile", label: "panel.my.profile", href: "/userbook/mon-compte?ui=react#/edit-me", current: !themes, show: true },
    { id: "notifications", label: "panel.notifications.set", href: "/timeline/preferencesView?ui=react", show: hasEmail && workflows.has(WF.EXTERNAL_NOTIFICATIONS) },
    { id: "history", label: "panel.user.history", href: "/timeline/historyView?ui=react", show: workflows.has(WF.HISTORY) },
    { id: "theme", label: "panel.user.pickTheme", href: "/userbook/mon-compte?ui=react#/themes", current: themes, show: workflows.has(WF.SWITCH_THEME) },
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
  const qc = useQueryClient();
  const workflows = workflowsOf(user);
  const [editing, setEditing] = useState<Editing>(null);
  const [validating, setValidating] = useState<"email" | "sms" | null>(null);
  // l'onglet « Thème » est la même page avec `#/themes` dans l'adresse
  const [themesTab, setThemesTab] = useState(window.location.hash.startsWith("#/themes"));
  useEffect(() => {
    const onHash = () => setThemesTab(window.location.hash.startsWith("#/themes"));
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [otp, setOtp] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [avatarVersion, setAvatarVersion] = useState(0);
  const context = useQuery({ queryKey: ["auth-context"], queryFn: accountService.authContext, staleTime: Infinity });

  if (account.isLoading) return <LoadingScreen position={false} />;
  const person = account.data?.person;
  const infos = account.data?.infos;
  const userId = account.data?.userId as string;
  const student = isStudent(person?.type);
  // ce que l'on peut modifier : l'élève ne change que son identifiant ; les autres profils leurs coordonnées
  const editable: Record<string, () => void> = {};
  if (!student) {
    editable.displayName = () => setEditing("displayName");
    editable.tel = () => setEditing("tel");
    editable.email = () => setValidating("email");
    editable.mobile = () => setValidating("sms");
  }
  if (workflows.has(WF.ALLOW_LOGIN_UPDATE)) editable.login = () => setEditing("login");
  const rows = profileRows(infos, person, Object.keys(editable));
  const me = user as { federated?: unknown; hasPw?: boolean; hasApp?: boolean } | undefined;
  // mot de passe : un compte fédéré ne le change que s'il en possède un ; mot de passe temporaire : comptes fédérés avec l'application mobile
  const canChangePassword = !me?.federated || !!me?.hasPw;
  const canGenerateOtp = !!me?.federated && !!me?.hasApp;
  const refresh = () => qc.invalidateQueries({ queryKey: ["account"] });
  const save = (body: Parameters<typeof accountService.update>[1]) => async (): Promise<void> => {
    await accountService.update(userId, body);
    await refresh();
  };

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
          <Tabs workflows={workflows} hasEmail={!!infos?.email} themes={themesTab} />
          {themesTab ? (
            <ThemePanel />
          ) : (
            <>
          {notice && (
            <div className="alert alert-success" role="status" data-account-notice>
              {notice}
            </div>
          )}
          <section data-account-profile className="d-flex gap-24 flex-wrap">
            <img
              src={`/userbook/avatar/${account.data?.userId}?thumbnail=100x100&v=${avatarVersion}`}
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
                  <dd className="mb-0">
                    {row.value}
                    {ROW_VISIBILITY[row.key] && (
                      <VisibilityToggle
                        id={ROW_VISIBILITY[row.key]}
                        visible={isVisible(person, ROW_VISIBILITY[row.key])}
                        onToggle={async () => {
                          await accountService.setInfoVisibility(ROW_VISIBILITY[row.key], nextVisibility(isVisible(person, ROW_VISIBILITY[row.key])));
                          await refresh();
                        }}
                      />
                    )}
                    {editable[row.key] && (
                      <Button type="button" color="primary" variant="ghost" size="sm" className="ms-8" onClick={editable[row.key]} data-account-edit={row.key}>
                        {t("directory.login.update.button")}
                      </Button>
                    )}
                  </dd>
                </div>
              ))}
            </dl>
          </section>
          <section className="d-flex gap-16 flex-wrap mt-24" data-account-actions>
            <label className="btn btn-outline-primary mb-0" data-account-avatar-change>
              {t("userBook.profile.changeavatar")}
              <input
                type="file"
                accept="image/*"
                hidden
                data-account-avatar-input
                onChange={async (e) => {
                  const file = e.target.files?.[0];
                  e.target.value = "";
                  if (!file) return;
                  try {
                    await accountService.uploadAvatar(userId, file);
                    setAvatarVersion((v) => v + 1);
                  } catch {
                    setNotice(t("account.error.save", { defaultValue: "L'enregistrement a échoué." }));
                  }
                }}
              />
            </label>
            <Button
              type="button"
              color="tertiary"
              variant="outline"
              onClick={async () => {
                await accountService.removeAvatar(userId);
                setAvatarVersion((v) => v + 1);
              }}
              data-account-avatar-remove
            >
              {t("userBook.remove", { defaultValue: "Supprimer" })}
            </Button>
            {canChangePassword && (
              <Button type="button" color="primary" variant="outline" onClick={() => setPasswordOpen(true)} data-account-password>
                {t("change.password")}
              </Button>
            )}
            {canGenerateOtp && (
              <Button
                type="button"
                color="primary"
                variant="outline"
                onClick={async () => setOtp((await accountService.generateOtp()).otp)}
                data-account-otp
              >
                {t("directory.generate.otp")}
              </Button>
            )}
          </section>
          <EditFieldModal
            id="account-edit-displayName"
            open={editing === "displayName"}
            title={t("directory.displayName.update.title")}
            label={t("userBook.profile.displayName")}
            initial={infos?.displayName ?? ""}
            validate={(v) => (v.trim() ? null : t("account.error.required", { defaultValue: "Ce champ est obligatoire." }))}
            onSave={(v) => save({ displayName: v.trim() })()}
            onClose={() => setEditing(null)}
          />
          <EditFieldModal
            id="account-edit-tel"
            open={editing === "tel"}
            type="tel"
            title={t("directory.telephone.update.title")}
            label={t("userBook.profile.telephone")}
            initial={infos?.homePhone ?? ""}
            validate={(v) => (isValidPhone(v) ? null : t("pattern"))}
            onSave={(v) => save({ homePhone: v })()}
            onClose={() => setEditing(null)}
          />
          <EditFieldModal
            id="account-edit-login"
            open={editing === "login"}
            title={t("directory.login.update.title")}
            label={t("userBook.profile.login")}
            initial={infos?.loginAlias || infos?.login || ""}
            maxLength={64}
            validate={(v) => (loginError(v) ? t("directory.form.login.pattern") : null)}
            onSave={async (v) => {
              try {
                await save({ loginAlias: v })();
              } catch (e) {
                const m = messageOf(e);
                throw new Error(apiErrorKind(m) === "exists" ? t("directory.notify.loginUpdate.error.alreadyExists") : (m ?? t("account.error.save", { defaultValue: "L'enregistrement a échoué." })));
              }
            }}
            onClose={() => setEditing(null)}
          />
          <ValidationModal
            kind={validating}
            onClose={() => {
              setValidating(null);
              void refresh();
            }}
          />
          <PasswordModal
            open={passwordOpen}
            login={infos?.login ?? person?.login ?? ""}
            regex={context.data?.passwordRegex ?? "^.{6,}$"}
            rule={context.data?.passwordRegexI18n?.fr}
            onClose={() => setPasswordOpen(false)}
            onDone={() => setNotice(t("account.password.done", { defaultValue: "Votre mot de passe a été modifié." }))}
          />
          <OtpModal login={infos?.login ?? person?.login ?? ""} otp={otp} onClose={() => setOtp(null)} />
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
          <UserbookPanel userId={userId} person={person} type={(user as { type?: string } | undefined)?.type} mottoAllowed={workflows.has(WF.MOTTO_MOOD)} />
            </>
          )}
        </>
      )}
    </Layout>
  );
};
