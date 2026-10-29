import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";

/** Nombre d'éléments affichés d'un coup, comme dans l'ancienne interface (50), puis par tranches de 50 au défilement. */
export const PAGE_SIZE = 50;

/**
 * Fin de liste : quand elle apparaît à l'écran, la tranche suivante s'affiche ; le bouton sert de repli (navigateur sans
 * IntersectionObserver, clavier). Rien n'est affiché quand tout l'est déjà.
 * Ex. 120 éléments : 50 affichés, « Afficher la suite (70) », puis 100, puis 120.
 */
export const LoadMore = ({ remaining, onMore }: { remaining: number; onMore: () => void }) => {
  const { t } = useTranslation("workspace");
  const end = useRef<HTMLDivElement>(null);
  const more = useRef(onMore);
  more.current = onMore;
  useEffect(() => {
    const node = end.current;
    if (!node || remaining <= 0 || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && more.current());
    observer.observe(node);
    return () => observer.disconnect();
  }, [remaining]);
  if (remaining <= 0) return null;
  return (
    <div ref={end} className="text-center my-16" data-load-more>
      <button type="button" className="btn btn-outline-secondary btn-sm" onClick={onMore}>
        {t("workspace.react.load.more", { count: remaining })}
      </button>
    </div>
  );
};
