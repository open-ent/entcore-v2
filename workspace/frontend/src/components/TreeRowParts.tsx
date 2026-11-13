import { ReactNode } from "react";
import { IconRafterRight } from "@open-ent/react/icons";

/**
 * Pièces communes aux lignes de l'arborescence (espace documentaire et NextCloud). Chaque ligne a la même grille :
 * une case de chevron, une case d'icône, puis le nom. Les cases ont une largeur fixe, qu'il y ait un chevron ou non,
 * pour que les icônes et les noms d'un même niveau tombent à l'aplomb les uns des autres.
 *
 * `.treeview` (socle) habille l'arbre (survol, emplacement courant) à partir des rôles ARIA ; `.action-container` est la
 * ligne cliquable qu'il met en valeur. Dimensions et retraits : `ws-tree-*` dans `WorkspaceStyle`.
 */
export const ROW = "action-container ws-tree-row d-flex align-items-center";

/** Chevron de dépliage, ou case vide de même largeur quand il n'y a rien à déplier. */
export const Chevron = ({
  expanded,
  onToggle,
  ...data
}: {
  expanded?: boolean;
  /** Absent : rien à déplier, la case reste vide (et occupe toujours sa place). */
  onToggle?: () => void;
} & Record<`data-${string}`, string | boolean | undefined>) =>
  onToggle ? (
    <button
      type="button"
      className="ws-tree-chevron"
      aria-label={expanded ? "−" : "+"}
      data-expanded={expanded ? "true" : undefined}
      onClick={onToggle}
      data-tree-toggle
      {...data}
    >
      <IconRafterRight width={14} height={14} />
    </button>
  ) : (
    <span className="ws-tree-chevron" aria-hidden="true" />
  );

/** Icône d'une ligne, colorée par les utilitaires du thème. */
export const RowIcon = ({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) => (
  <span className={`ws-tree-icon ${className}`} aria-hidden="true">
    {children}
  </span>
);
