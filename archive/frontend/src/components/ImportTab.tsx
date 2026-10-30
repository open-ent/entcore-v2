import { RichText } from "./RichText";
import { DragEvent, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  countDuplicateResources,
  countMainResources,
  ImportReport,
  NO_DUPLICATE_COUNT_APPS,
  quotaExceeded,
  readableSize,
  selectedImportSize,
  toggleAll,
} from "../lib/archive";
import { archiveService, HttpError, ImportAnalysis, UploadHandle } from "../services/archive.service";

type Phase = "choose" | "uploading" | "analysed" | "importing" | "report";

const Size = ({ bytes }: { bytes: number }) => {
  const { t } = useTranslation("archive");
  const s = readableSize(bytes);
  return (
    <span>
      {s.quantity} {t(s.unit)}
    </span>
  );
};

/**
 * Import d'une archive (.zip) : on dépose ou on choisit le fichier, il est envoyé puis analysé, on coche les applications à restaurer
 * (en vérifiant le quota disponible), puis un rapport indique pour chacune ce qui a été importé, les doublons et les erreurs.
 */
export const ImportTab = () => {
  const { t } = useTranslation("archive");
  const [phase, setPhase] = useState<Phase>("choose");
  const [notice, setNotice] = useState<string | null>(null);
  const [file, setFile] = useState<{ name: string; size: number } | null>(null);
  const [progress, setProgress] = useState(0);
  const [over, setOver] = useState(false);
  const [analysis, setAnalysis] = useState<ImportAnalysis | null>(null);
  const [importId, setImportId] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [results, setResults] = useState<Record<string, ImportReport>>({});
  const upload = useRef<UploadHandle | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const reset = () => {
    setPhase("choose");
    setFile(null);
    setProgress(0);
    setAnalysis(null);
    setImportId(null);
    setSelected([]);
    if (input.current) input.current.value = "";
  };
  const cancel = (id?: string | null) => {
    if (id) void archiveService.cancelImport(id);
    reset();
  };

  const start = async (f: File) => {
    setNotice(null);
    setFile({ name: f.name, size: f.size });
    setPhase("uploading");
    setProgress(0);
    let id: string;
    try {
      upload.current = archiveService.upload(f, setProgress);
      id = (await upload.current.promise).importId;
    } catch (e) {
      if (!(e instanceof HttpError && e.status === -1)) setNotice(t(e instanceof HttpError && e.status === 413 ? "archive.import.toobig" : "archive.import.already"));
      reset();
      return;
    }
    setImportId(id);
    try {
      const a = await archiveService.analyze(id);
      if (Object.keys(a.apps).length === 0) {
        setNotice(t("archive.import.none.available"));
        cancel(id);
        return;
      }
      setAnalysis(a);
      setPhase("analysed");
    } catch {
      setNotice(t("archive.import.corrupted"));
      cancel(null);
    }
  };

  const abort = () => {
    if (phase === "uploading") upload.current?.abort();
    cancel(importId);
  };

  const launch = async () => {
    if (!importId || !analysis) return;
    setPhase("importing");
    const apps = Object.fromEntries(Object.entries(analysis.apps).filter(([a]) => selected.includes(a)));
    try {
      const report = await archiveService.launch(importId, apps);
      setResults(report);
      setPhase("report");
    } catch {
      setNotice(t("archive.import.failure"));
      cancel(importId);
    }
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    const f = e.dataTransfer.files[0];
    if (f && phase === "choose") void start(f);
  };

  const available = analysis ? Object.keys(analysis.apps) : [];
  const total = analysis ? selectedImportSize(analysis.apps, selected) : 0;
  const exceeded = analysis ? quotaExceeded(total, analysis.quota) : false;

  return (
    <section data-archive-import>
      {notice && (
        <div className="alert alert-danger" role="alert" data-archive-notice>
          {notice}
        </div>
      )}
      {(phase === "choose" || phase === "uploading" || phase === "analysed") && (
        <>
          <div className="alert alert-warning"><RichText text={t("import.info")} /></div>
          {phase === "choose" && (
            <div
              className="p-32 text-center rounded"
              style={{ border: `2px dashed ${over ? "#2a9cc8" : "#c5c5c5"}`, background: over ? "#eef7fb" : "transparent" }}
              onDragOver={(e) => {
                e.preventDefault();
                setOver(true);
              }}
              onDragLeave={() => setOver(false)}
              onDrop={onDrop}
              data-import-dropzone
            >
              <p>
                <em>{t("import.drop-files")}</em>
              </p>
              <button type="button" className="btn btn-outline-primary" onClick={() => input.current?.click()} data-import-browse>
                {t("import.browse")}
              </button>
              <p className="mt-8">
                <em>{t("import.browse-files")}</em>
              </p>
              <input
                ref={input}
                type="file"
                accept=".zip"
                hidden
                data-import-input
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) void start(f);
                }}
              />
            </div>
          )}
          {file && phase !== "choose" && (
            <div className="d-flex align-items-center gap-16 p-16 border rounded" data-import-file>
              <strong>{file.name}</strong>
              <Size bytes={file.size} />
              {phase === "uploading" && (
                <progress max={100} value={progress} aria-label={file.name} data-import-progress>
                  {progress}%
                </progress>
              )}
              <button type="button" className="btn btn-link ms-auto" onClick={abort} aria-label={t("archive.cancel")} data-import-abort>
                ✕
              </button>
            </div>
          )}
        </>
      )}
      {phase === "analysed" && analysis && (
        <div className="mt-24" data-import-analysis>
          <p>{t("archive.quota.explanation")}</p>
          <p>
            {t("archive.quota.available")} : <Size bytes={analysis.quota} />
          </p>
          <p style={exceeded ? { color: "red" } : undefined} data-import-quota-selected>
            {t("archive.quota.selected")} : <Size bytes={total} />
            {exceeded && <> — {t("archive.quota.warning")}</>}
          </p>
          <p>{t("archive.import.select")}</p>
          <label className="d-block mt-8">
            <input type="checkbox" checked={available.every((a) => selected.includes(a))} onChange={() => setSelected(toggleAll(available, selected))} data-import-all /> {t("archive.all")}
          </label>
          <div className="ms-32" style={{ columnWidth: 260, columnGap: 24 }}>
            {available.map((app) => (
              <label key={app} className="d-block mt-8">
                <input type="checkbox" checked={selected.includes(app)} onChange={() => setSelected((s) => (s.includes(app) ? s.filter((a) => a !== app) : [...s, app]))} data-import-app={app} /> {t(app)}
                {analysis.apps[app].size > 0 && (
                  <>
                    {" "}
                    (<Size bytes={analysis.apps[app].size} />)
                  </>
                )}
              </label>
            ))}
          </div>
          <hr />
          <div className="d-flex justify-content-end gap-12">
            <button type="button" className="btn btn-outline-secondary" onClick={() => cancel(importId)} data-import-cancel>
              {t("archive.cancel")}
            </button>
            <button type="button" className="btn btn-primary" disabled={selected.length === 0 || exceeded} onClick={() => void launch()} data-import-launch>
              {t("archive.import")}
            </button>
          </div>
        </div>
      )}
      {phase === "importing" && (
        <p className="text-center" role="status" data-import-loading>
          {t("archive.import.loading")}
        </p>
      )}
      {phase === "report" && (
        <table className="table w-100" data-import-report>
          <thead>
            <tr>
              <th>{t("archive.report.application")}</th>
              <th>{t("archive.report.imported")}</th>
              <th>{t("archive.report.duplicates")}</th>
              <th>{t("archive.report.errors")}</th>
            </tr>
          </thead>
          <tbody>
            {Object.keys(results).map((app) => (
              <tr key={app} data-report-app={app}>
                <td>{t(app)}</td>
                <td>{countMainResources(results[app])}</td>
                <td>{NO_DUPLICATE_COUNT_APPS.includes(app) ? t("archive.import.na") : countDuplicateResources(results[app])}</td>
                <td>{results[app].errorsNumber}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
};
