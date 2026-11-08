import { AppHeader } from "@open-ent/react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { ViewerModal } from "../components/ViewerModal";

export { Content } from "../components/ViewerContent";

/**
 * Page d'un document (`/view/:id`) : lien direct, rechargement, et l'action « Retoucher l'image » de la liste
 * (`?edit=image`). Elle ouvre la même fenêtre que la liste, pour n'entretenir qu'un seul rendu, et revient d'où l'on
 * venait à la fermeture.
 */
export const Viewer = () => {
  const { t } = useTranslation("workspace");
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const nav = (location.state as { ids?: string[]; from?: string } | null) ?? {};
  const siblings = nav.ids ?? [];
  const from = nav.from ?? "/owner";
  const editImage = new URLSearchParams(location.search).get("edit") === "image";

  return (
    <>
      <AppHeader>
        <h1 className="h2">{t("workspace.title")}</h1>
      </AppHeader>
      <ViewerModal
        id={id}
        siblings={siblings}
        editImage={editImage}
        onClose={() => navigate(from)}
        onCurrentChange={(next) => navigate(`/view/${next}`, { state: { ids: siblings, from }, replace: true })}
      />
    </>
  );
};
