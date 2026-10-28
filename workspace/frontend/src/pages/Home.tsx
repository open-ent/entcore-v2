import { AppHeader } from "@open-ent/react";
import { useTranslation } from "react-i18next";

/** Écran d'accueil de l'interface React (les lots W1 et suivants y branchent l'arborescence et la liste). */
export const Home = () => {
  const { t } = useTranslation("workspace");
  return (
    <>
      <AppHeader>
        <h1 className="h2" data-workspace-title>
          {t("workspace.title")}
        </h1>
      </AppHeader>
      <p data-workspace-react>{t("workspace.react.preview")}</p>
    </>
  );
};
