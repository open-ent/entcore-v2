import { useTranslation } from "react-i18next";
import { ActionId } from "../lib/actions";

const LABEL: Record<ActionId, string> = {
  rename: "workspace.rename",
  copy: "workspace.react.action.copy",
  move: "workspace.react.action.move",
  trash: "workspace.move.trash",
  restore: "workspace.trash.restore",
  delete: "workspace.react.action.delete",
  download: "workspace.react.action.download",
  share: "workspace.react.action.share",
  publish: "workspace.document.publish",
  history: "workspace.versions.list",
  comments: "workspace.react.action.comments",
  location: "workspace.open.location",
  openLool: "workspace.external.open",
  openScratch: "workspace.external.open",
  openGeogebra: "workspace.external.open",
};

/** Barre d'actions de la sélection (les actions viennent de `availableActions`). */
export const ActionBar = ({
  actions,
  count,
  onAction,
  onClear,
}: {
  actions: ActionId[];
  count: number;
  onAction: (a: ActionId) => void;
  onClear: () => void;
}) => {
  const { t } = useTranslation("workspace");
  if (actions.length === 0) return null;
  return (
    <div className="d-flex flex-wrap gap-8 align-items-center mb-16" role="toolbar" data-action-bar>
      <span data-selection-count>{t("workspace.react.selected", { count })}</span>
      {actions.map((a) => (
        <button key={a} type="button" className="btn btn-outline-primary btn-sm" data-action={a} onClick={() => onAction(a)}>
          {t(LABEL[a])}
        </button>
      ))}
      <button type="button" className="btn btn-link btn-sm" onClick={onClear}>
        {t("workspace.react.select.none")}
      </button>
    </div>
  );
};
