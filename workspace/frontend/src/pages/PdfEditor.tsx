import { AppHeader, Button } from "@open-ent/react";
import type { PDFDocumentProxy, PDFPageProxy } from "pdfjs-dist";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { ConfirmModal } from "../components/ConfirmModal";
import { PAGE_WIDTH, PdfPageView, Tool } from "../components/PdfPageView";
import { SignaturePad } from "../components/SignaturePad";
import {
  Annotation,
  COLORS,
  EncryptedPdfError,
  FieldValue,
  PageRef,
  PdfField,
  applyEdits,
  initialPages,
  loadPdf,
  movePage,
  readFields,
  turnPage,
} from "../lib/pdfEdit";
import { loadPdfLibs } from "../lib/pdfLibs";
import { revisionService } from "../services/workspace.service";

/** pdf.js et ses ressources, servis par les assets partagés de l'ENT (comme la numérisation du socle). */
const PDF_ASSETS = "/assets/ocr";

const TOOLS: Tool[] = ["select", "text", "highlight", "rect", "ink", "signature"];
const SIZES = [10, 12, 14, 18, 24];

type Loaded = { bytes: Uint8Array; pdf: PDFDocumentProxy; pages: PDFPageProxy[]; fields: PdfField[]; name: string };

const loadDocument = async (id: string): Promise<Loaded> => {
  const [res, props] = await Promise.all([
    fetch(`/workspace/document/${id}`, { credentials: "same-origin" }),
    fetch(`/workspace/document/properties/${id}`, { credentials: "same-origin" }).then((r) => (r.ok ? r.json() : {})).catch(() => ({})),
  ]);
  if (!res.ok) throw new Error("workspace.pdfeditor.load.failed");
  const bytes = new Uint8Array(await res.arrayBuffer());
  const doc = await loadPdf(bytes); // refuse un PDF chiffré
  const { pdfjs } = await loadPdfLibs();
  pdfjs.GlobalWorkerOptions.workerSrc = `${PDF_ASSETS}/pdf.worker.min.js`;
  // pdf.js reçoit une copie : il transfère (et vide) le tampon qu'on lui donne
  const pdf = await pdfjs.getDocument({
    data: bytes.slice(),
    wasmUrl: `${PDF_ASSETS}/pdf-wasm/`,
    standardFontDataUrl: `${PDF_ASSETS}/pdf-fonts/`,
    useSystemFonts: false,
  }).promise;
  const pages = await Promise.all(Array.from({ length: pdf.numPages }, (_, i) => pdf.getPage(i + 1)));
  return { bytes, pdf, pages, fields: await readFields(doc), name: (props as { name?: string }).name ?? "document.pdf" };
};

/**
 * Éditeur PDF (`/edit/pdf/:id`) : annoter (texte, surlignage, cadre, dessin, signature ou tampon),
 * remplir le formulaire, réorganiser les pages (ordre, quart de tour, suppression, page blanche).
 * Tout se fait dans le navigateur (pdf.js pour l'affichage, pdf-lib pour l'écriture) ; l'enregistrement
 * crée une nouvelle version du document, l'historique des versions permet de revenir en arrière.
 */
export const PdfEditor = () => {
  const { t } = useTranslation("workspace");
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? "/owner";

  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [pages, setPages] = useState<PageRef[]>([]);
  const [annotations, setAnnotations] = useState<Annotation[]>([]);
  const [fields, setFields] = useState<Record<string, FieldValue>>({});
  const [flattenForm, setFlattenForm] = useState(false);
  const [tool, setTool] = useState<Tool>("select");
  const [colorKey, setColorKey] = useState<keyof typeof COLORS>("blue");
  const [size, setSize] = useState(14);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [signature, setSignature] = useState<{ png: Uint8Array; ratio: number } | null>(null);
  const [signing, setSigning] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const textInput = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    let alive = true;
    loadDocument(id)
      .then((l) => {
        if (!alive) return;
        setLoaded(l);
        setPages(initialPages(l.pages.length));
      })
      .catch((e) => alive && setLoadError(e instanceof EncryptedPdfError ? "workspace.pdfeditor.encrypted" : "workspace.pdfeditor.load.failed"));
    return () => {
      alive = false;
    };
  }, [id]);
  useEffect(() => () => void loaded?.pdf.destroy(), [loaded]);

  const color = COLORS[colorKey];
  const selected = annotations.find((a) => a.id === selectedId) ?? null;
  const touched = () => setDirty(true);

  const add = useCallback((a: Annotation) => {
    setAnnotations((list) => [...list, a]);
    setDirty(true);
  }, []);
  const change = useCallback((a: Annotation) => {
    setAnnotations((list) => list.map((x) => (x.id === a.id ? a : x)));
    setDirty(true);
  }, []);
  const remove = useCallback((annotationId: string) => {
    setAnnotations((list) => list.filter((x) => x.id !== annotationId));
    setSelectedId(null);
    setDirty(true);
  }, []);

  // Suppr / Retour arrière : retire l'annotation choisie (hors saisie de texte)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement || e.target instanceof HTMLSelectElement;
      if (!typing && selectedId && (e.key === "Delete" || e.key === "Backspace")) {
        e.preventDefault();
        remove(selectedId);
      }
      if (e.key === "Escape") setTool("select");
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [selectedId, remove]);

  // un texte qui vient d'être posé se saisit tout de suite
  useEffect(() => {
    if (selected?.kind === "text" && !selected.text) textInput.current?.focus();
  }, [selected]);

  const changedFields = useMemo(() => {
    const initial = Object.fromEntries((loaded?.fields ?? []).map((f) => [f.name, f.value]));
    return Object.fromEntries(Object.entries(fields).filter(([k, v]) => initial[k] !== v));
  }, [fields, loaded]);

  const save = async () => {
    if (!loaded) return;
    setSaving(true);
    setSaveError(null);
    try {
      const out = await applyEdits(loaded.bytes, {
        pages,
        // un texte vide ne s'écrit pas
        annotations: annotations.filter((a) => a.kind !== "text" || a.text.trim()),
        fields: changedFields,
        flattenForm,
      });
      const file = new File([out], loaded.name.toLowerCase().endsWith(".pdf") ? loaded.name : `${loaded.name}.pdf`, { type: "application/pdf" });
      await revisionService.add(id, file);
      setDirty(false);
      navigate(from, { state: { notice: "workspace.pdfeditor.saved" } });
    } catch (e) {
      setSaveError(t(typeof e === "string" ? e : e instanceof Error && e.message.startsWith("workspace.") ? e.message : "workspace.pdfeditor.save.failed"));
      setSaving(false);
    }
  };

  const close = () => (dirty ? setLeaving(true) : navigate(from));

  const pickTool = (next: Tool) => {
    if (next === "signature" && !signature) {
      setSigning(true);
      return;
    }
    // le surlignage se fait en jaune ; le jaune ne se lit pas pour un texte ou un trait
    if (next === "highlight") setColorKey("yellow");
    else if (colorKey === "yellow" && (next === "text" || next === "rect" || next === "ink")) setColorKey("blue");
    setTool(next);
  };

  if (loadError || !loaded) {
    return (
      <>
        <AppHeader>
          <h1 className="h2">{t("workspace.pdfeditor.title")}</h1>
        </AppHeader>
        <div className="p-24 text-center" data-pdf-editor-state={loadError ? "error" : "loading"}>
          {loadError ? (
            <>
              <p className="text-danger" role="alert">{t(loadError)}</p>
              <Button type="button" color="primary" onClick={() => navigate(from)}>
                {t("workspace.pdfeditor.back")}
              </Button>
            </>
          ) : (
            <p>{t("workspace.pdfeditor.loading")}</p>
          )}
        </div>
      </>
    );
  }

  return (
    <>
      <AppHeader>
        <h1 className="h2 text-truncate" title={loaded.name}>
          {t("workspace.pdfeditor.title")} — {loaded.name}
        </h1>
      </AppHeader>
      <div className="d-flex flex-wrap align-items-center gap-8 p-8 border-bottom bg-white sticky-top" data-pdf-toolbar>
        <div className="btn-group" role="group" aria-label={t("workspace.pdfeditor.tools")}>
          {TOOLS.map((x) => (
            <Button
              key={x}
              type="button"
              size="sm"
              variant={tool === x ? "filled" : "outline"}
              color="primary"
              onClick={() => pickTool(x)}
              aria-pressed={tool === x}
              data-pdf-tool={x}
            >
              {t(`workspace.pdfeditor.tool.${x}`)}
            </Button>
          ))}
        </div>
        <span className="vr mx-4" aria-hidden="true" />
        <div className="d-flex align-items-center gap-4" role="radiogroup" aria-label={t("workspace.pdfeditor.color")}>
          {(Object.keys(COLORS) as (keyof typeof COLORS)[]).map((k) => (
            <button
              key={k}
              type="button"
              role="radio"
              aria-checked={colorKey === k}
              aria-label={t(`workspace.pdfeditor.color.${k}`)}
              title={t(`workspace.pdfeditor.color.${k}`)}
              onClick={() => setColorKey(k)}
              className="rounded-circle border"
              style={{
                width: 22,
                height: 22,
                background: `rgb(${COLORS[k].r * 255}, ${COLORS[k].g * 255}, ${COLORS[k].b * 255})`,
                outline: colorKey === k ? "2px solid #2a6edb" : undefined,
                outlineOffset: 2,
              }}
            />
          ))}
        </div>
        <label className="d-flex align-items-center gap-4 mb-0 small">
          {t("workspace.pdfeditor.size")}
          <select className="form-select form-select-sm" value={size} onChange={(e) => setSize(Number(e.target.value))} data-pdf-size>
            {SIZES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <span className="ms-auto" />
        <Button type="button" variant="ghost" color="tertiary" onClick={close} disabled={saving}>
          {t("workspace.pdfeditor.close")}
        </Button>
        <Button type="button" color="primary" onClick={save} isLoading={saving} disabled={saving || !dirty} data-pdf-save>
          {t("workspace.pdfeditor.save")}
        </Button>
      </div>
      {saveError && (
        <p className="text-danger px-16 pt-8 mb-0" role="alert" data-pdf-error>
          {saveError}
        </p>
      )}

      <div className="d-flex align-items-start gap-16 p-16">
        <aside className="flex-shrink-0" style={{ width: 280 }} data-pdf-sidebar>
          {selected && (
            <section className="card p-12 mb-16" data-pdf-selected={selected.kind}>
              <h2 className="h6">{t(`workspace.pdfeditor.selected.${selected.kind}`)}</h2>
              {selected.kind === "text" && (
                <>
                  <label htmlFor="pdf-text" className="small">
                    {t("workspace.pdfeditor.text.label")}
                  </label>
                  <textarea
                    id="pdf-text"
                    ref={textInput}
                    className="form-control"
                    rows={4}
                    value={selected.text}
                    onChange={(e) => change({ ...selected, text: e.target.value })}
                    data-pdf-text-input
                  />
                  <label className="d-flex align-items-center gap-4 mt-8 small">
                    {t("workspace.pdfeditor.size")}
                    <select className="form-select form-select-sm" value={selected.size} onChange={(e) => change({ ...selected, size: Number(e.target.value) })}>
                      {SIZES.map((s) => (
                        <option key={s} value={s}>
                          {s}
                        </option>
                      ))}
                    </select>
                  </label>
                </>
              )}
              {selected.kind === "image" && (
                <Button type="button" size="sm" variant="ghost" color="tertiary" className="mt-8 me-8" onClick={() => setSigning(true)}>
                  {t("workspace.pdfeditor.signature.change")}
                </Button>
              )}
              <Button type="button" size="sm" color="danger" variant="ghost" className="mt-8" onClick={() => remove(selected.id)} data-pdf-remove>
                {t("workspace.pdfeditor.remove")}
              </Button>
            </section>
          )}

          <section className="card p-12 mb-16" data-pdf-pages>
            <h2 className="h6">{t("workspace.pdfeditor.pages")}</h2>
            <ol className="list-unstyled mb-8">
              {pages.map((p, i) => (
                <li key={"blank" in p ? `blank-${i}` : `p-${p.source}`} className="d-flex align-items-center gap-4 py-4 border-bottom" data-pdf-page-row={i + 1}>
                  <span className="flex-fill small">
                    {"blank" in p
                      ? t("workspace.pdfeditor.page.blank", { n: i + 1 })
                      : t("workspace.pdfeditor.page.of", { n: i + 1, source: p.source + 1 })}
                    {"turn" in p && p.turn ? ` · ${p.turn}°` : ""}
                  </span>
                  <button type="button" className="btn btn-sm btn-ghost-tertiary px-4" disabled={i === 0} onClick={() => (setPages(movePage(pages, i, -1)), touched())} aria-label={t("workspace.pdfeditor.page.up", { n: i + 1 })}>↑</button>
                  <button type="button" className="btn btn-sm btn-ghost-tertiary px-4" disabled={i === pages.length - 1} onClick={() => (setPages(movePage(pages, i, 1)), touched())} aria-label={t("workspace.pdfeditor.page.down", { n: i + 1 })}>↓</button>
                  {"source" in p && (
                    <button type="button" className="btn btn-sm btn-ghost-tertiary px-4" onClick={() => (setPages(turnPage(pages, i)), touched())} aria-label={t("workspace.pdfeditor.page.turn", { n: i + 1 })} data-pdf-page-turn={i + 1}>↻</button>
                  )}
                  <button
                    type="button"
                    className="btn btn-sm btn-ghost-danger px-4"
                    disabled={pages.length === 1}
                    onClick={() => (setPages(pages.filter((_, j) => j !== i)), touched())}
                    aria-label={t("workspace.pdfeditor.page.remove", { n: i + 1 })}
                    data-pdf-page-remove={i + 1}
                  >
                    ✕
                  </button>
                </li>
              ))}
            </ol>
            <Button
              type="button"
              size="sm"
              variant="outline"
              color="primary"
              onClick={() => {
                const first = loaded.pages[0]?.getViewport({ scale: 1 });
                setPages([...pages, { blank: true, width: first?.width ?? 595, height: first?.height ?? 842 }]);
                touched();
              }}
              data-pdf-page-blank
            >
              {t("workspace.pdfeditor.page.add.blank")}
            </Button>
          </section>

          {loaded.fields.length > 0 && (
            <section className="card p-12" data-pdf-form>
              <h2 className="h6">{t("workspace.pdfeditor.form")}</h2>
              {loaded.fields.map((f) => {
                const value = f.name in fields ? fields[f.name] : f.value;
                const set = (v: FieldValue) => (setFields((x) => ({ ...x, [f.name]: v })), touched());
                const idAttr = `pdf-field-${f.name.replace(/[^A-Za-z0-9_-]/g, "_")}`;
                if (f.type === "checkbox")
                  return (
                    <div key={f.name} className="form-check d-flex align-items-center gap-8 mb-8">
                      <input id={idAttr} type="checkbox" className="form-check-input" checked={!!value} disabled={f.readOnly} onChange={(e) => set(e.target.checked)} data-pdf-field={f.name} />
                      <label className="form-check-label" htmlFor={idAttr}>{f.name}</label>
                    </div>
                  );
                return (
                  <div key={f.name} className="mb-8">
                    <label htmlFor={idAttr} className="small">{f.name}</label>
                    {f.options ? (
                      <select id={idAttr} className="form-select form-select-sm" value={String(value)} disabled={f.readOnly} onChange={(e) => set(e.target.value)} data-pdf-field={f.name}>
                        <option value="" />
                        {f.options.map((o) => (
                          <option key={o} value={o}>{o}</option>
                        ))}
                      </select>
                    ) : f.multiline ? (
                      <textarea id={idAttr} className="form-control form-control-sm" rows={3} value={String(value)} disabled={f.readOnly} onChange={(e) => set(e.target.value)} data-pdf-field={f.name} />
                    ) : (
                      <input id={idAttr} className="form-control form-control-sm" value={String(value)} disabled={f.readOnly} onChange={(e) => set(e.target.value)} data-pdf-field={f.name} />
                    )}
                  </div>
                );
              })}
              <p className="small text-gray-700 mb-0">{t("workspace.pdfeditor.form.preview")}</p>
              <div className="form-check d-flex align-items-center gap-8 mt-8">
                <input id="pdf-flatten" type="checkbox" className="form-check-input" checked={flattenForm} onChange={(e) => (setFlattenForm(e.target.checked), touched())} data-pdf-flatten />
                <label className="form-check-label small" htmlFor="pdf-flatten">{t("workspace.pdfeditor.form.flatten")}</label>
              </div>
            </section>
          )}
          <p className="small text-gray-700 mt-16">{t("workspace.pdfeditor.versions.hint")}</p>
        </aside>

        <main className="flex-fill overflow-auto" style={{ minWidth: 0 }} data-pdf-pages-view>
          {pages.map((p, i) =>
            "blank" in p ? (
              <div key={`blank-${i}`} className="mx-auto mb-24" style={{ width: PAGE_WIDTH }} data-pdf-page={i + 1}>
                <div className="small text-gray-700 mb-4">{t("workspace.pdfeditor.page", { n: i + 1 })}</div>
                <div className="bg-white shadow-sm d-flex align-items-center justify-content-center text-gray-500" style={{ width: PAGE_WIDTH, height: (PAGE_WIDTH * p.height) / p.width }}>
                  {t("workspace.pdfeditor.page.blank.hint")}
                </div>
              </div>
            ) : (
              <PdfPageView
                key={`p-${p.source}`}
                proxy={loaded.pages[p.source]}
                source={p.source}
                turn={p.turn}
                number={i + 1}
                annotations={annotations.filter((a) => a.page === p.source)}
                selectedId={selectedId}
                tool={tool}
                color={color}
                size={size}
                signature={signature}
                onAdd={add}
                onChange={change}
                onSelect={setSelectedId}
                onToolDone={() => setTool("select")}
              />
            ),
          )}
        </main>
      </div>

      <SignaturePad
        isOpen={signing}
        onClose={() => setSigning(false)}
        onDone={(png, ratio) => {
          setSignature({ png, ratio });
          setSigning(false);
          setTool("signature");
        }}
      />
      <ConfirmModal
        isOpen={leaving}
        title={t("workspace.pdfeditor.leave.title")}
        message={t("workspace.pdfeditor.leave.message")}
        confirmLabel={t("workspace.pdfeditor.leave.confirm")}
        onConfirm={() => navigate(from)}
        onClose={() => setLeaving(false)}
      />
    </>
  );
};
