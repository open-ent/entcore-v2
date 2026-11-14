import { Button, Modal } from "@open-ent/react";
import { ChangeEvent, PointerEvent, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

const WIDTH = 520;
const HEIGHT = 200;

/** Contenu d'une toile en PNG, rogné à ce qui est dessiné (marge de 8 px) ; null si elle est vide. */
const trimmedPng = async (canvas: HTMLCanvasElement): Promise<Uint8Array | null> => {
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  const { data, width, height } = ctx.getImageData(0, 0, canvas.width, canvas.height);
  let x0 = width, y0 = height, x1 = -1, y1 = -1;
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++)
      if (data[(y * width + x) * 4 + 3] > 0) {
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        if (y > y1) y1 = y;
      }
  if (x1 < 0) return null;
  const m = 8;
  const out = document.createElement("canvas");
  out.width = Math.min(width, x1 - x0 + 1 + 2 * m);
  out.height = Math.min(height, y1 - y0 + 1 + 2 * m);
  out.getContext("2d")?.drawImage(canvas, Math.max(0, x0 - m), Math.max(0, y0 - m), out.width, out.height, 0, 0, out.width, out.height);
  const blob = await new Promise<Blob | null>((resolve) => out.toBlob(resolve, "image/png"));
  return blob ? new Uint8Array(await blob.arrayBuffer()) : null;
};

/**
 * Signature manuscrite ou tampon : dessinée à la souris, au doigt ou au stylet, ou importée depuis
 * une image du poste. Rend un PNG transparent, rogné. Signature simple, sans valeur probante.
 */
export const SignaturePad = ({
  isOpen,
  onDone,
  onClose,
}: {
  isOpen: boolean;
  onDone: (png: Uint8Array, ratio: number) => void;
  onClose: () => void;
}) => {
  const { t } = useTranslation("workspace");
  const canvas = useRef<HTMLCanvasElement>(null);
  const drawing = useRef<{ x: number; y: number } | null>(null);
  const [empty, setEmpty] = useState(true);

  useEffect(() => {
    if (!isOpen) return;
    setEmpty(true);
    // la toile n'existe qu'une fois la fenêtre ouverte
    requestAnimationFrame(() => canvas.current?.getContext("2d")?.clearRect(0, 0, WIDTH, HEIGHT));
  }, [isOpen]);

  const point = (e: PointerEvent<HTMLCanvasElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return { x: ((e.clientX - r.left) * WIDTH) / r.width, y: ((e.clientY - r.top) * HEIGHT) / r.height };
  };
  const down = (e: PointerEvent<HTMLCanvasElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    drawing.current = point(e);
  };
  const move = (e: PointerEvent<HTMLCanvasElement>) => {
    const from = drawing.current;
    const ctx = canvas.current?.getContext("2d");
    if (!from || !ctx) return;
    const to = point(e);
    ctx.strokeStyle = "#10204b";
    ctx.lineWidth = 3;
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.beginPath();
    ctx.moveTo(from.x, from.y);
    ctx.lineTo(to.x, to.y);
    ctx.stroke();
    drawing.current = to;
    setEmpty(false);
  };
  const up = () => (drawing.current = null);

  const importImage = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    const ctx = canvas.current?.getContext("2d");
    if (!file || !ctx) return;
    const image = await createImageBitmap(file);
    const scale = Math.min(WIDTH / image.width, HEIGHT / image.height, 1);
    const w = image.width * scale;
    const h = image.height * scale;
    ctx.clearRect(0, 0, WIDTH, HEIGHT);
    ctx.drawImage(image, (WIDTH - w) / 2, (HEIGHT - h) / 2, w, h);
    setEmpty(false);
  };

  const validate = async () => {
    if (!canvas.current) return;
    const png = await trimmedPng(canvas.current);
    if (!png) return;
    const probe = await createImageBitmap(new Blob([png], { type: "image/png" }));
    onDone(png, probe.width / probe.height);
  };

  return (
    <Modal id="workspace-signature" isOpen={isOpen} onModalClose={onClose} size="lg">
      <Modal.Header onModalClose={onClose}>{t("workspace.pdfeditor.signature.title")}</Modal.Header>
      <Modal.Body>
        <p className="text-gray-700">{t("workspace.pdfeditor.signature.help")}</p>
        <canvas
          ref={canvas}
          width={WIDTH}
          height={HEIGHT}
          className="border rounded-3 w-100 bg-white"
          style={{ touchAction: "none", cursor: "crosshair", aspectRatio: `${WIDTH} / ${HEIGHT}` }}
          onPointerDown={down}
          onPointerMove={move}
          onPointerUp={up}
          onPointerLeave={up}
          aria-label={t("workspace.pdfeditor.signature.area")}
          data-signature-canvas
        />
        <div className="d-flex gap-8 mt-8">
          <Button type="button" size="sm" variant="ghost" color="tertiary" onClick={() => { canvas.current?.getContext("2d")?.clearRect(0, 0, WIDTH, HEIGHT); setEmpty(true); }}>
            {t("workspace.pdfeditor.signature.clear")}
          </Button>
          <label className="btn btn-ghost-tertiary btn-sm mb-0">
            {t("workspace.pdfeditor.signature.import")}
            <input type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={importImage} data-signature-import />
          </label>
        </div>
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" color="tertiary" variant="ghost" onClick={onClose}>
          {t("cancel")}
        </Button>
        <Button type="button" color="primary" disabled={empty} onClick={validate} data-signature-validate>
          {t("workspace.pdfeditor.signature.use")}
        </Button>
      </Modal.Footer>
    </Modal>
  );
};
