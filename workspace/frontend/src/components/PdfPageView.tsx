import type { PDFPageProxy, PageViewport } from "pdfjs-dist";
import { PointerEvent, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Annotation, Box, Color, Point, boxFrom } from "../lib/pdfEdit";

export type Tool = "select" | "text" | "highlight" | "rect" | "ink" | "signature";

/** Largeur d'affichage d'une page, en pixels CSS. */
export const PAGE_WIDTH = 820;

const css = (c: Color, alpha = 1) => `rgba(${Math.round(c.r * 255)}, ${Math.round(c.g * 255)}, ${Math.round(c.b * 255)}, ${alpha})`;

type Rect = { left: number; top: number; width: number; height: number };

/** Zone PDF → rectangle à l'écran (quel que soit le sens de la page). */
const toScreen = (vp: PageViewport, b: Box): Rect => {
  const [x1, y1, x2, y2] = vp.convertToViewportRectangle([b.x, b.y, b.x + b.w, b.y + b.h]);
  return { left: Math.min(x1, x2), top: Math.min(y1, y2), width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) };
};
const toPdf = (vp: PageViewport, p: Point): Point => {
  const [x, y] = vp.convertToPdfPoint(p.x, p.y);
  return { x, y };
};

let counter = 0;
export const newId = () => `a${Date.now().toString(36)}${(counter++).toString(36)}`;

type Drag =
  | { mode: "draw"; start: Point; current: Point }
  | { mode: "ink"; points: Point[] }
  | { mode: "move"; start: Point; base: Annotation }
  | { mode: "resize"; anchor: Point; base: Annotation };

/**
 * Une page d'origine : rendu pdf.js dans l'orientation finale (rotation propre + quart de tour) et
 * calque des annotations. Les outils travaillent à l'écran et convertissent en points PDF.
 */
export const PdfPageView = ({
  proxy,
  source,
  turn,
  number,
  annotations,
  selectedId,
  tool,
  color,
  size,
  signature,
  onAdd,
  onChange,
  onSelect,
  onToolDone,
}: {
  proxy: PDFPageProxy;
  source: number;
  turn: number;
  /** Numéro affiché (position dans le document produit). */
  number: number;
  annotations: Annotation[];
  selectedId: string | null;
  tool: Tool;
  color: Color;
  size: number;
  signature: { png: Uint8Array; ratio: number } | null;
  onAdd: (a: Annotation) => void;
  onChange: (a: Annotation) => void;
  onSelect: (id: string | null) => void;
  onToolDone: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const canvas = useRef<HTMLCanvasElement>(null);
  const layer = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [visible, setVisible] = useState(typeof IntersectionObserver === "undefined");

  const vp = useMemo(() => {
    const rotation = (proxy.rotate + turn) % 360;
    const natural = proxy.getViewport({ scale: 1, rotation });
    return proxy.getViewport({ scale: PAGE_WIDTH / natural.width, rotation });
  }, [proxy, turn]);
  const scale = vp.scale;

  // rendu paresseux : seulement quand la page approche de l'écran
  useEffect(() => {
    if (typeof IntersectionObserver === "undefined" || !layer.current) return;
    const io = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && setVisible(true), { rootMargin: "600px" });
    io.observe(layer.current);
    return () => io.disconnect();
  }, []);

  useEffect(() => {
    const c = canvas.current;
    if (!visible || !c) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = Math.floor(vp.width * dpr);
    c.height = Math.floor(vp.height * dpr);
    const ctx = c.getContext("2d");
    if (!ctx) return;
    const task = proxy.render({ canvas: c, canvasContext: ctx, viewport: vp, transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : undefined });
    task.promise.catch(() => undefined);
    return () => task.cancel();
  }, [proxy, vp, visible]);

  const local = (e: PointerEvent): Point => {
    const r = layer.current!.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.target !== e.currentTarget) return; // clic sur une annotation : géré par elle
    const p = local(e);
    if (tool === "select") {
      onSelect(null);
      return;
    }
    if (tool === "text") {
      const lineH = size * scale * 1.2;
      const box = boxFrom(toPdf(vp, p), toPdf(vp, { x: p.x + 260, y: p.y + lineH * 2 }));
      const a: Annotation = { id: newId(), page: source, kind: "text", ...box, text: "", size, color };
      onAdd(a);
      onSelect(a.id);
      onToolDone();
      return;
    }
    if (tool === "signature") {
      if (!signature) return;
      const w = 200;
      const h = w / signature.ratio;
      const box = boxFrom(toPdf(vp, { x: p.x - w / 2, y: p.y - h / 2 }), toPdf(vp, { x: p.x + w / 2, y: p.y + h / 2 }));
      const a: Annotation = { id: newId(), page: source, kind: "image", ...box, png: signature.png };
      onAdd(a);
      onSelect(a.id);
      onToolDone();
      return;
    }
    e.currentTarget.setPointerCapture(e.pointerId);
    setDrag(tool === "ink" ? { mode: "ink", points: [p] } : { mode: "draw", start: p, current: p });
  };

  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    if (!drag) return;
    const p = local(e);
    if (drag.mode === "ink") setDrag({ mode: "ink", points: [...drag.points, p] });
    else if (drag.mode === "draw") setDrag({ ...drag, current: p });
    else if (drag.mode === "move") {
      const from = toPdf(vp, drag.start);
      const to = toPdf(vp, p);
      const dx = to.x - from.x;
      const dy = to.y - from.y;
      const b = drag.base;
      onChange(b.kind === "ink" ? { ...b, points: b.points.map((q) => ({ x: q.x + dx, y: q.y + dy })) } : { ...b, x: b.x + dx, y: b.y + dy });
    } else if (drag.mode === "resize" && drag.base.kind !== "ink") {
      const box = boxFrom(toPdf(vp, drag.anchor), toPdf(vp, p));
      if (box.w > 2 && box.h > 2) onChange({ ...drag.base, ...box });
    }
  };

  const onPointerUp = () => {
    if (!drag) return;
    if (drag.mode === "draw" && (tool === "highlight" || tool === "rect")) {
      const box = boxFrom(toPdf(vp, drag.start), toPdf(vp, drag.current));
      if (Math.abs(drag.current.x - drag.start.x) > 4 && Math.abs(drag.current.y - drag.start.y) > 4) {
        const a: Annotation =
          tool === "highlight"
            ? { id: newId(), page: source, kind: "highlight", ...box, color }
            : { id: newId(), page: source, kind: "rect", ...box, color, width: Math.max(1, size / 6) };
        onAdd(a);
      }
    } else if (drag.mode === "ink" && drag.points.length > 1) {
      onAdd({ id: newId(), page: source, kind: "ink", points: drag.points.map((p) => toPdf(vp, p)), color, width: Math.max(1, size / 6) });
    }
    setDrag(null);
  };

  const startMove = (a: Annotation, e: PointerEvent) => {
    onSelect(a.id);
    if (tool !== "select") return;
    e.stopPropagation();
    layer.current?.setPointerCapture(e.pointerId);
    setDrag({ mode: "move", start: local(e), base: a });
  };
  const startResize = (a: Annotation, r: Rect, e: PointerEvent) => {
    e.stopPropagation();
    layer.current?.setPointerCapture(e.pointerId);
    setDrag({ mode: "resize", anchor: { x: r.left, y: r.top }, base: a });
  };

  const preview =
    drag?.mode === "draw" ? (
      <div
        className="position-absolute"
        style={{
          left: Math.min(drag.start.x, drag.current.x),
          top: Math.min(drag.start.y, drag.current.y),
          width: Math.abs(drag.current.x - drag.start.x),
          height: Math.abs(drag.current.y - drag.start.y),
          background: tool === "highlight" ? css(color, 0.35) : undefined,
          border: tool === "rect" ? `2px solid ${css(color)}` : undefined,
          pointerEvents: "none",
        }}
      />
    ) : null;

  const inkLines = annotations.filter((a): a is Extract<Annotation, { kind: "ink" }> => a.kind === "ink");

  return (
    <div className="ws-pdf-page mx-auto mb-24" style={{ width: vp.width }} data-pdf-page={number} data-pdf-source={source}>
      <div className="small text-gray-700 mb-4">{t("workspace.pdfeditor.page", { n: number })}</div>
      <div className="position-relative bg-white shadow-sm" style={{ width: vp.width, height: vp.height }}>
        <canvas ref={canvas} style={{ width: vp.width, height: vp.height, display: "block" }} />
        <div
          ref={layer}
          className="position-absolute top-0 start-0"
          style={{ width: vp.width, height: vp.height, cursor: tool === "select" ? "default" : "crosshair", touchAction: "none" }}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          data-pdf-layer
        >
          <svg className="position-absolute top-0 start-0" width={vp.width} height={vp.height} style={{ pointerEvents: "none" }}>
            {inkLines.map((a) => (
              <polyline
                key={a.id}
                points={a.points.map((q) => vp.convertToViewportPoint(q.x, q.y).join(",")).join(" ")}
                fill="none"
                stroke={css(a.color)}
                strokeWidth={a.width * scale}
                strokeLinecap="round"
                strokeLinejoin="round"
                style={{ pointerEvents: "stroke", cursor: "move" }}
                onPointerDown={(e) => startMove(a, e)}
                data-annotation="ink"
                opacity={selectedId === a.id ? 0.7 : 1}
              />
            ))}
            {drag?.mode === "ink" && (
              <polyline points={drag.points.map((q) => `${q.x},${q.y}`).join(" ")} fill="none" stroke={css(color)} strokeWidth={Math.max(1, size / 6) * scale} strokeLinecap="round" />
            )}
          </svg>
          {annotations
            .filter((a) => a.kind !== "ink")
            .map((a) => {
              const r = toScreen(vp, a);
              const selected = selectedId === a.id;
              return (
                <div
                  key={a.id}
                  className="position-absolute"
                  style={{
                    ...r,
                    outline: selected ? "2px dashed #2a6edb" : undefined,
                    cursor: tool === "select" ? "move" : undefined,
                    background: a.kind === "highlight" ? css(a.color, 0.35) : undefined,
                    border: a.kind === "rect" ? `${a.width * scale}px solid ${css(a.color)}` : undefined,
                  }}
                  onPointerDown={(e) => startMove(a, e)}
                  data-annotation={a.kind}
                  data-annotation-id={a.id}
                >
                  {a.kind === "text" && (
                    <div
                      style={{ fontFamily: "Helvetica, Arial, sans-serif", fontSize: a.size * scale, lineHeight: 1.2, color: css(a.color), whiteSpace: "pre", minHeight: "100%" }}
                      data-annotation-text
                    >
                      {a.text || (selected ? "" : t("workspace.pdfeditor.text.empty"))}
                    </div>
                  )}
                  {a.kind === "image" && <PngImage png={a.png} />}
                  {selected && a.kind !== "text" && (
                    <span
                      className="position-absolute bg-white border border-primary"
                      style={{ right: -6, bottom: -6, width: 12, height: 12, cursor: "nwse-resize" }}
                      onPointerDown={(e) => startResize(a, r, e)}
                      aria-hidden="true"
                      data-annotation-resize
                    />
                  )}
                </div>
              );
            })}
          {preview}
        </div>
      </div>
    </div>
  );
};

/** Aperçu d'un PNG en mémoire (URL d'objet libérée au démontage). */
const PngImage = ({ png }: { png: Uint8Array }) => {
  const [url, setUrl] = useState<string>();
  useEffect(() => {
    const u = URL.createObjectURL(new Blob([png], { type: "image/png" }));
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [png]);
  return url ? <img src={url} alt="" draggable={false} style={{ width: "100%", height: "100%", pointerEvents: "none" }} /> : null;
};
