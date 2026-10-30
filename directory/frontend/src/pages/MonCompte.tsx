import { AppHeader, Layout } from "@open-ent/react";
import { useTranslation } from "react-i18next";

/** Mon compte (migration en cours : profil, mot de passe, avatar, visibilité des informations). */
export const MonCompte = () => {
  const { t } = useTranslation("directory");
  return (
    <Layout>
      <AppHeader>
        <h1 className="h2" data-account-title>
          {t("account.myaccount")}
        </h1>
      </AppHeader>
    </Layout>
  );
};
