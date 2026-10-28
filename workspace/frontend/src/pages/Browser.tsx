import { AppHeader, LoadingScreen, SegmentedControl, useEdificeClient } from "@open-ent/react";
import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, Navigate, useLocation, useParams } from "react-router-dom";
import { ElementList } from "../components/ElementList";
import { QuotaGauge } from "../components/QuotaGauge";
import { TreeNav } from "../components/TreeNav";
import { breadcrumb, isTreeName, sortElements, SortKey } from "../lib/elements";
import { ViewMode } from "../lib/types";
import { useContent, useFolders, useQuota, useViewModePreference } from "../services/queries";

/** Navigation dans l'espace documentaire : arbres, dossiers, fil d'Ariane, liste en icônes ou en tableau, jauge d'espace. */
export const Browser = () => {
  const { t } = useTranslation("workspace");
  const { tree, folderId } = useParams();
  const { search } = useLocation();
  const [view, setView] = useState<ViewMode>("icons");
  const [sort, setSort] = useState<SortKey>("name");

  const preference = useViewModePreference();
  useEffect(() => {
    if (preference.data) setView(preference.data);
  }, [preference.data]);

  const validTree = isTreeName(tree) ? tree : "owner";
  const content = useContent(validTree, folderId);
  const folders = useFolders();
  const { user } = useEdificeClient();
  const quota = useQuota(user?.userId);

  const path = useMemo(() => breadcrumb(folders.data ?? [], folderId ?? null), [folders.data, folderId]);
  const elements = useMemo(() => sortElements(content.data ?? [], sort), [content.data, sort]);

  if (!isTreeName(tree)) return <Navigate to={{ pathname: "/workspace/owner", search }} replace />;

  return (
    <>
      <AppHeader>
        <h1 className="h2" data-workspace-title>
          {t("workspace.title")}
        </h1>
      </AppHeader>
      <div className="d-flex gap-24 flex-wrap">
        <aside style={{ minWidth: 240 }}>
          <TreeNav tree={validTree} folderId={folderId} folders={folders.data ?? []} />
          {quota.data && <QuotaGauge quota={quota.data} />}
        </aside>
        <main style={{ flex: 1, minWidth: 0 }}>
          <nav aria-label={t("workspace.react.breadcrumb")} data-workspace-breadcrumb className="mb-12">
            <Link to={{ pathname: `/workspace/${validTree}`, search }}>{t(`workspace.react.tree.${validTree}`)}</Link>
            {path.map((f) => (
              <span key={f._id}>
                {" / "}
                <Link to={{ pathname: `/workspace/${validTree}/${f._id}`, search }}>{f.name}</Link>
              </span>
            ))}
          </nav>
          <div className="d-flex gap-16 align-items-center mb-16">
            <SegmentedControl
              options={[
                { label: t("workspace.react.view.icons"), value: "icons" },
                { label: t("workspace.react.view.list"), value: "list" },
              ]}
              value={view}
              onChange={(v: string) => setView(v === "list" ? "list" : "icons")}
            />
            <label>
              {t("workspace.react.sort")}{" "}
              <select value={sort} onChange={(e) => setSort(e.target.value as SortKey)} data-workspace-sort>
                <option value="name">{t("name")}</option>
                <option value="modified">{t("modified")}</option>
                <option value="size">{t("size")}</option>
              </select>
            </label>
          </div>
          {content.isLoading && <LoadingScreen />}
          {content.isError && (
            <div className="alert alert-danger" role="alert" data-workspace-error>
              {t("workspace.react.error")}{" "}
              <button type="button" className="btn btn-link p-0" onClick={() => content.refetch()}>
                {t("workspace.react.retry")}
              </button>
            </div>
          )}
          {content.isSuccess && elements.length === 0 && <p data-workspace-empty>{t("workspace.react.empty")}</p>}
          {content.isSuccess && elements.length > 0 && <ElementList tree={validTree} elements={elements} view={view} />}
        </main>
      </div>
    </>
  );
};
