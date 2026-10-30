import { AppHeader, Layout } from "@open-ent/react";
import { useTranslation } from "react-i18next";

/** Annuaire (migration en cours : recherche, fiches, favoris, groupes). */
export const Annuaire = () => {
  const { t } = useTranslation("directory");
  return (
    <Layout>
      <AppHeader>
        <h1 className="h2" data-annuaire-title>
          {t("userBook.annuaire.title", { defaultValue: "Annuaire" })}
        </h1>
      </AppHeader>
    </Layout>
  );
};
