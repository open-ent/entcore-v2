import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { appsToExport, exportableApps, ExportMode, toggleAll } from "../lib/archive";
import { archiveService, HttpError } from "../services/archive.service";

/** Délai avant de vérifier que l'archive est prête et de la télécharger (comme l'ancienne interface). */
const READY_DELAY_MS = 5000;

/**
 * Export de ses données : on coche les applications à archiver, le serveur prépare un fichier, puis le téléchargement démarre.
 * Un compte dont les applications sont déjà retirées (avant suppression) propose « documents seulement » ou « tout ».
 */
export const ExportTab = () => {
  const { t } = useTranslation("archive");
  const [selected, setSelected] = useState<string[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  // un export lancé depuis cette page : on attend qu'il soit prêt
  const [waiting, setWaiting] = useState(false);
  const [spinner, setSpinner] = useState(false);

  const apps = useQuery({
    queryKey: ["archive", "apps"],
    queryFn: async () => {
      const [exportable, mine] = await Promise.all([archiveService.exportableApps(), archiveService.myApps()]);
      const activated = exportableApps(mine, exportable, (k) => t(k));
      return { activated, everything: exportable, preDeleted: activated.length === 0 };
    },
  });
  // un export déjà en cours ou prêt (lancé avant, depuis un autre onglet…)
  const verify = useQuery({ queryKey: ["archive", "export-exists"], queryFn: archiveService.exportExists, staleTime: 0 });
  useEffect(() => {
    if (verify.error) setNotice(t(verify.error instanceof HttpError && verify.error.status === 404 ? "archive.error.404" : "archive.error"));
  }, [verify.error, t]);

  const exists = verify.data === true;
  const busy = exists || waiting;
  const available = apps.data?.activated ?? [];

  const run = async (mode: ExportMode) => {
    setNotice(null);
    let started: { exportId: string };
    try {
      started = await archiveService.startExport(appsToExport(mode, selected, apps.data?.everything ?? []));
    } catch {
      setNotice(t("export.already"));
      return;
    }
    setWaiting(true);
    setSpinner(true);
    window.setTimeout(async () => {
      try {
        await archiveService.verifyExport(started.exportId);
        window.location.href = archiveService.downloadUrl(started.exportId);
        setSpinner(false);
      } catch (e) {
        setNotice(t(e instanceof HttpError && e.status === 404 ? "archive.error.404" : "archive.error"));
        window.setTimeout(() => window.location.reload(), 3000);
      }
    }, READY_DELAY_MS);
  };

  const checkbox = (app: string) => (
    <label key={app} className="d-block me-24 mt-8">
      <input type="checkbox" checked={selected.includes(app)} onChange={() => setSelected((s) => (s.includes(app) ? s.filter((a) => a !== app) : [...s, app]))} data-export-app={app} />{" "}
      {t(app)}
    </label>
  );

  return (
    <section data-archive-export>
      {notice && (
        <div className="alert alert-danger" role="alert" data-archive-notice>
          {notice}
        </div>
      )}
      {apps.isLoading && <p>{t("archive.loading", { defaultValue: "…" })}</p>}
      {apps.isSuccess && !apps.data.preDeleted && !busy && (
        <div>
          <div className="alert alert-info">{t("archive.info")}</div>
          <label className="d-block mt-16">
            <input type="checkbox" checked={available.length > 0 && available.every((a) => selected.includes(a))} onChange={() => setSelected(toggleAll(available, selected))} data-export-all /> {t("archive.all")}
          </label>
          <div className="ms-32">{available.map(checkbox)}</div>
          <hr />
          <button type="button" className="btn btn-primary" disabled={selected.length === 0} onClick={() => void run("apps")} data-export-launch>
            {t("export")}
          </button>
        </div>
      )}
      {apps.isSuccess && apps.data.preDeleted && !busy && (
        <div className="text-center">
          <button type="button" className="btn btn-primary" onClick={() => void run("docsOnly")} data-export-docs>
            {t("export.docs")}
          </button>
          <p className="alert alert-info mt-12">{t("archive.download.docs")}</p>
          <hr />
          <button type="button" className="btn btn-primary" onClick={() => void run("all")} data-export-everything>
            {t("export.all")}
          </button>
          <p className="alert alert-info mt-12">{t("archive.download.all")}</p>
        </div>
      )}
      {busy && (
        <p className="text-center" data-export-waiting>
          {exists && !waiting ? t("archive.exists") : t("archive.waiting")}
        </p>
      )}
      {spinner && (
        <p className="text-center" role="status" data-export-spinner>
          …
        </p>
      )}
    </section>
  );
};
