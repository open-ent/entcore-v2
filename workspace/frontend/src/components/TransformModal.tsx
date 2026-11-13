import { Button, Modal } from "@open-ent/react";
import { FormEvent, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { cleanName, isForbiddenChar, MAX_NAME } from "../lib/properties";
import {
  conversionTargets,
  extensionOf,
  OPERATION,
  pagesSelection,
  passwordStrength,
  suggestedName,
  TransformId,
  VERSIONABLE,
} from "../lib/transform";
import { WorkspaceElement } from "../lib/types";
import { TransformResult, transformDocuments } from "../services/transform.service";

const STRENGTH_KEYS = ["weak", "fair", "good", "strong"] as const;

/**
 * Fenêtre d'une transformation (menu « Transformer ») : options propres à l'opération, nom du
 * résultat et destination (nouveau document dans le dossier courant, ou nouvelle version quand le
 * format ne change pas). Les erreurs du serveur s'affichent dans la fenêtre, traduites.
 */
export const TransformModal = ({
  transform,
  selection,
  parentId,
  canVersion,
  onDone,
  onClose,
}: {
  transform: TransformId | null;
  selection: WorkspaceElement[];
  /** Dossier où créer le résultat (absent : racine de « Mes documents »). */
  parentId?: string;
  /** L'usager peut-il remplacer le document par une nouvelle version ? */
  canVersion: boolean;
  onDone: (result: TransformResult, transform: TransformId) => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const [name, setName] = useState("");
  const [target, setTarget] = useState<"new" | "version">("new");
  const [order, setOrder] = useState<WorkspaceElement[]>([]);
  const [format, setFormat] = useState("");
  const [pages, setPages] = useState("");
  const [rotate, setRotate] = useState("0");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [noCopy, setNoCopy] = useState(true);
  const [noPrint, setNoPrint] = useState(false);
  const [noModify, setNoModify] = useState(true);
  const [dpi, setDpi] = useState("150");
  const [level, setLevel] = useState("ebook");
  const [pageSize, setPageSize] = useState("a4");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const ext = selection[0] ? extensionOf(selection[0]) : "";
  const targets = conversionTargets(ext).filter((x) => x !== "pdf");

  useEffect(() => {
    if (!transform) return;
    setName(suggestedName(transform, selection, (k, o) => t(k, o)));
    setTarget("new");
    setOrder(selection);
    setFormat(transform === "pdfToImages" ? "png" : targets[0] ?? "");
    setPages("");
    setRotate("0");
    setPassword("");
    setConfirm("");
    setError(null);
    setBusy(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [transform]);

  if (!transform) return null;

  const move = (i: number, delta: number) => {
    const next = [...order];
    const j = i + delta;
    if (j < 0 || j >= next.length) return;
    [next[i], next[j]] = [next[j], next[i]];
    setOrder(next);
  };

  const options = (): Record<string, unknown> | string => {
    switch (transform) {
      case "toPdf":
        return { to: "pdf" };
      case "convert":
        return { to: format };
      case "pdfPages": {
        const select = pages.trim() ? pagesSelection(pages) : "1-z";
        if (!select) return "workspace.transform.pages.invalid";
        return rotate === "0" ? { select } : { select, rotate: [rotate] };
      }
      case "protectPdf":
        if (password !== confirm) return "workspace.transform.password.mismatch";
        if (!password && !noCopy && !noPrint && !noModify) return "workspace.transform.encrypt.nothing";
        return { userPassword: password, allowCopy: !noCopy, allowPrint: !noPrint, allowModify: !noModify };
      case "unlockPdf":
        return { password };
      case "rasterizePdf":
        return { dpi: Number(dpi) };
      case "compressPdf":
        return { level };
      case "pdfToImages":
        return { format, dpi: Number(dpi) };
      case "imagesToPdf":
        return { pagesize: pageSize };
      default:
        return {};
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const opts = options();
    if (typeof opts === "string") {
      setError(t(opts));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await transformDocuments({
        operation: OPERATION[transform],
        ids: order.map((x) => x._id),
        options: opts,
        target,
        parentId: target === "new" ? parentId : undefined,
        name: target === "new" ? cleanName(name) || undefined : undefined,
      });
      onDone(result, transform);
    } catch (key) {
      setError(t(typeof key === "string" ? key : "workspace.transform.failed"));
      setBusy(false);
    }
  };

  const ordered = transform === "mergePdf" || (transform === "imagesToPdf" && order.length > 1);
  const strength = passwordStrength(password);
  const versionable = canVersion && VERSIONABLE.includes(transform) && selection.length === 1;

  return (
    <Modal id="workspace-transform" isOpen onModalClose={onClose} focusId="workspace-transform-submit" size="md">
      <Modal.Header onModalClose={onClose}>{t(`workspace.transform.${transform}`)}</Modal.Header>
      <form onSubmit={submit} data-transform-form={transform}>
        <Modal.Body>
          <p className="text-gray-700">{t(`workspace.transform.${transform}.help`)}</p>

          {ordered && (
            <fieldset className="mb-16">
              <legend className="fs-6 fw-bold">{t("workspace.transform.order")}</legend>
              <ol className="list-unstyled mb-0" data-transform-order>
                {order.map((el, i) => (
                  <li key={el._id} className="d-flex align-items-center gap-8 py-4 border-bottom">
                    <span className="flex-fill text-truncate">
                      {i + 1}. {el.name}
                    </span>
                    <Button type="button" size="sm" variant="ghost" color="tertiary" disabled={i === 0} onClick={() => move(i, -1)} aria-label={t("workspace.transform.up", { name: el.name })}>
                      ↑
                    </Button>
                    <Button type="button" size="sm" variant="ghost" color="tertiary" disabled={i === order.length - 1} onClick={() => move(i, 1)} aria-label={t("workspace.transform.down", { name: el.name })}>
                      ↓
                    </Button>
                  </li>
                ))}
              </ol>
            </fieldset>
          )}

          {transform === "convert" && (
            <div className="mb-16">
              <label htmlFor="workspace-transform-format">{t("workspace.transform.format")}</label>
              <select id="workspace-transform-format" className="form-select" value={format} onChange={(e) => setFormat(e.target.value)} data-transform-format>
                {targets.map((x) => (
                  <option key={x} value={x}>
                    {x.toUpperCase()}
                  </option>
                ))}
              </select>
              {ext === "pdf" && <p className="small text-gray-700 mt-8">{t("workspace.transform.convert.pdf.approx")}</p>}
            </div>
          )}

          {transform === "pdfPages" && (
            <div className="mb-16">
              <label htmlFor="workspace-transform-pages">{t("workspace.transform.pages.select")}</label>
              <input id="workspace-transform-pages" className="form-control" value={pages} placeholder="1-3, 5, 8-fin" onChange={(e) => setPages(e.target.value)} data-transform-pages />
              <p className="small text-gray-700 mt-4">{t("workspace.transform.pages.hint")}</p>
              <label htmlFor="workspace-transform-rotate" className="mt-8">
                {t("workspace.transform.rotate")}
              </label>
              <select id="workspace-transform-rotate" className="form-select" value={rotate} onChange={(e) => setRotate(e.target.value)} data-transform-rotate>
                <option value="0">{t("workspace.transform.rotate.none")}</option>
                <option value="90">{t("workspace.transform.rotate.right")}</option>
                <option value="180">{t("workspace.transform.rotate.half")}</option>
                <option value="270">{t("workspace.transform.rotate.left")}</option>
              </select>
            </div>
          )}

          {(transform === "protectPdf" || transform === "unlockPdf") && (
            <div className="mb-16">
              <label htmlFor="workspace-transform-password">{t(transform === "protectPdf" ? "workspace.transform.password.open" : "workspace.transform.password")}</label>
              <input id="workspace-transform-password" type="password" className="form-control" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} data-transform-password />
              {transform === "protectPdf" && (
                <>
                  {password && (
                    <p className={`small mt-4 ${strength < 2 ? "text-danger" : "text-success"}`} data-transform-strength={strength}>
                      {t(`workspace.transform.strength.${STRENGTH_KEYS[strength]}`)}
                    </p>
                  )}
                  <label htmlFor="workspace-transform-confirm" className="mt-8">
                    {t("workspace.transform.password.confirm")}
                  </label>
                  <input id="workspace-transform-confirm" type="password" className="form-control" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} data-transform-confirm />
                  <p className="small text-gray-700 mt-4">{t("workspace.transform.password.empty.hint")}</p>
                  {[
                    ["noCopy", noCopy, setNoCopy],
                    ["noPrint", noPrint, setNoPrint],
                    ["noModify", noModify, setNoModify],
                  ].map(([key, value, set]) => (
                    <div className="form-check d-flex align-items-center gap-8" key={key as string}>
                      <input
                        id={`workspace-transform-${key}`}
                        type="checkbox"
                        className="form-check-input"
                        checked={value as boolean}
                        onChange={(e) => (set as (v: boolean) => void)(e.target.checked)}
                        data-transform-restriction={key as string}
                      />
                      <label className="form-check-label" htmlFor={`workspace-transform-${key}`}>
                        {t(`workspace.transform.restrict.${key}`)}
                      </label>
                    </div>
                  ))}
                  <p className="small text-gray-700 mt-8">{t("workspace.transform.restrict.warning")}</p>
                </>
              )}
            </div>
          )}

          {(transform === "rasterizePdf" || transform === "pdfToImages") && (
            <div className="mb-16">
              {transform === "pdfToImages" && (
                <>
                  <label htmlFor="workspace-transform-imgformat">{t("workspace.transform.format")}</label>
                  <select id="workspace-transform-imgformat" className="form-select mb-8" value={format} onChange={(e) => setFormat(e.target.value)}>
                    <option value="png">PNG</option>
                    <option value="jpg">JPEG</option>
                  </select>
                </>
              )}
              <label htmlFor="workspace-transform-dpi">{t("workspace.transform.quality")}</label>
              <select id="workspace-transform-dpi" className="form-select" value={dpi} onChange={(e) => setDpi(e.target.value)} data-transform-dpi>
                <option value="100">{t("workspace.transform.quality.screen")}</option>
                <option value="150">{t("workspace.transform.quality.standard")}</option>
                <option value="300">{t("workspace.transform.quality.print")}</option>
              </select>
              {transform === "rasterizePdf" && (
                <p className="small text-warning-emphasis mt-8" data-transform-warning>
                  {t("workspace.transform.rasterizePdf.warning")}
                </p>
              )}
            </div>
          )}

          {transform === "compressPdf" && (
            <div className="mb-16">
              <label htmlFor="workspace-transform-level">{t("workspace.transform.level")}</label>
              <select id="workspace-transform-level" className="form-select" value={level} onChange={(e) => setLevel(e.target.value)}>
                <option value="screen">{t("workspace.transform.level.screen")}</option>
                <option value="ebook">{t("workspace.transform.level.ebook")}</option>
                <option value="printer">{t("workspace.transform.level.printer")}</option>
              </select>
            </div>
          )}

          {transform === "imagesToPdf" && (
            <div className="mb-16">
              <label htmlFor="workspace-transform-pagesize">{t("workspace.transform.pagesize")}</label>
              <select id="workspace-transform-pagesize" className="form-select" value={pageSize} onChange={(e) => setPageSize(e.target.value)}>
                <option value="a4">{t("workspace.transform.pagesize.a4")}</option>
                <option value="image">{t("workspace.transform.pagesize.image")}</option>
              </select>
            </div>
          )}

          {versionable && (
            <fieldset className="mb-16" data-transform-target>
              <legend className="fs-6 fw-bold">{t("workspace.transform.target")}</legend>
              <div className="form-check d-flex align-items-center gap-8">
                <input id="workspace-transform-new" type="radio" className="form-check-input" checked={target === "new"} onChange={() => setTarget("new")} />
                <label className="form-check-label" htmlFor="workspace-transform-new">
                  {t("workspace.transform.target.new")}
                </label>
              </div>
              <div className="form-check d-flex align-items-center gap-8">
                <input id="workspace-transform-version" type="radio" className="form-check-input" checked={target === "version"} onChange={() => setTarget("version")} data-transform-version />
                <label className="form-check-label" htmlFor="workspace-transform-version">
                  {t("workspace.transform.target.version")}
                </label>
              </div>
            </fieldset>
          )}

          {target === "new" && (
            <div className="mb-8">
              <label htmlFor="workspace-transform-name">{t("workspace.document.name")}</label>
              <input
                id="workspace-transform-name"
                className="form-control"
                value={name}
                maxLength={MAX_NAME}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                  if (isForbiddenChar(e.key)) e.preventDefault();
                }}
                autoComplete="off"
                data-transform-name
              />
            </div>
          )}

          {error && (
            <p className="text-danger mt-8" role="alert" data-transform-error>
              {error}
            </p>
          )}
        </Modal.Body>
        <Modal.Footer>
          <Button type="button" color="tertiary" variant="ghost" onClick={onClose} disabled={busy}>
            {t("cancel")}
          </Button>
          <Button id="workspace-transform-submit" type="submit" color="primary" isLoading={busy} disabled={busy} data-transform-submit>
            {t("workspace.transform.run")}
          </Button>
        </Modal.Footer>
      </form>
    </Modal>
  );
};
