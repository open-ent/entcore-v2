import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { fileKind } from "../lib/elements";
import { WorkspaceElement } from "../lib/types";

/** Images du dossier affiché, dans l'ordre de la liste (les autres documents n'entrent pas dans le diaporama). */
export const carouselImages = (elements: WorkspaceElement[]): WorkspaceElement[] =>
  elements.filter((e) => e.eType === "file" && fileKind(e.metadata?.["content-type"]) === "image");

/**
 * Affichage « diaporama » (troisième affichage de l'ancienne interface) : une image du dossier à la fois, précédente/suivante
 * et pastilles pour aller à une image. Ex. un dossier de 3 images et 2 PDF → 3 pastilles, les PDF sont ignorés.
 */
export const Carousel = ({ elements }: { elements: WorkspaceElement[] }) => {
  const { t } = useTranslation("workspace");
  const images = carouselImages(elements);
  const [index, setIndex] = useState(0);
  // le dossier change ou des images disparaissent : on reste dans les bornes
  useEffect(() => setIndex((i) => Math.min(i, Math.max(images.length - 1, 0))), [images.length]);
  if (images.length === 0) return <p data-carousel-empty>{t("workspace.react.carousel.empty")}</p>;
  const current = images[index];
  const go = (i: number) => setIndex((i + images.length) % images.length);
  return (
    <div data-carousel className="text-center" style={{ maxWidth: 720 }}>
      <div className="d-flex align-items-center justify-content-center gap-16">
        <button type="button" className="btn btn-outline-secondary" onClick={() => go(index - 1)} aria-label={t("workspace.react.viewer.previous")} data-carousel-previous disabled={images.length < 2}>
          ‹
        </button>
        <img src={`/workspace/document/${current._id}`} alt={current.name} style={{ maxWidth: "100%", maxHeight: 420 }} data-carousel-image />
        <button type="button" className="btn btn-outline-secondary" onClick={() => go(index + 1)} aria-label={t("workspace.react.viewer.next")} data-carousel-next disabled={images.length < 2}>
          ›
        </button>
      </div>
      <p className="mt-8 mb-8" data-carousel-name>
        {current.name} ({index + 1}/{images.length})
      </p>
      <div className="d-flex justify-content-center gap-8" role="tablist">
        {images.map((img, i) => (
          <button
            key={img._id}
            type="button"
            role="tab"
            aria-selected={i === index}
            aria-label={img.name}
            onClick={() => setIndex(i)}
            data-carousel-dot
            style={{ width: 12, height: 12, borderRadius: "50%", border: 0, padding: 0, background: i === index ? "#2a9cc8" : "#c5c5c5" }}
          />
        ))}
      </div>
    </div>
  );
};
