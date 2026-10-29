import { ActionId } from "../lib/actions";
import { BarLayout } from "../lib/actionGroups";
import { SelectionBar } from "./SelectionBar";

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
  ncCopy: "nextcloud.share.copy.action",
  ncMove: "nextcloud.share.move.action",
  driveExport: "workspace.cloud.drive.export.action",
};

/** Partager (partage, portail public, NextCloud) · Organiser (copier, déplacer, vers NextCloud ou Drive) · Plus (versions, commentaires). */
export const LAYOUT: BarLayout<ActionId> = {
  direct: ["download", "rename", "openLool", "openScratch", "openGeogebra", "location", "restore"],
  menus: [
    { key: "share", label: "workspace.react.menu.share", ids: ["share", "publish", "ncCopy"] },
    { key: "organise", label: "workspace.react.menu.organise", ids: ["copy", "move", "ncMove", "driveExport"] },
    { key: "more", label: "workspace.react.menu.more", ids: ["history", "comments"] },
  ],
  danger: ["trash", "delete"],
};

/** Barre d'actions de la sélection (les actions viennent de `availableActions`). */
export const ActionBar = ({ actions, count, onAction, onClear }: { actions: ActionId[]; count: number; onAction: (a: ActionId) => void; onClear: () => void }) => (
  <SelectionBar actions={actions} layout={LAYOUT} labels={LABEL} count={count} attr={{ bar: "data-action-bar", action: "data-action" }} onAction={onAction} onClear={onClear} />
);
