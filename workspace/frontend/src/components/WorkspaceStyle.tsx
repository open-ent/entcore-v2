/**
 * Mise en page de l'espace documentaire que les classes du socle n'expriment pas : grille des cartes, colonne de gauche,
 * en-têtes de section, repères de sélection et de dépôt, et l'adaptation aux petits écrans (moins de 768 px, comme
 * `zero-mobile` de l'ancienne interface : propriétaire, date et poids disparaissent des listes, la colonne de gauche passe
 * au-dessus du contenu, les barres d'actions défilent au lieu de déborder).
 *
 * Tout le reste de l'habillage vient du bootstrap `@open-ent`, chargé au runtime par la vue : aucune couleur n'est écrite
 * ici, on emploie les variables du thème pour que les skins d'établissement suivent.
 */
export const WORKSPACE_CSS = `
.ws-aside { flex: 0 0 26rem; max-width: 100%; }
.ws-main { flex: 1 1 32rem; min-width: 0; }

/* Repères communs : élément sélectionné, cible d'un glisser-déposer. */
.ws-selected { outline: 2px solid var(--edifice-primary); outline-offset: 1px; }
.ws-drop-target { outline: 2px dashed var(--edifice-primary); outline-offset: 2px; }

/* Grille des dossiers et des documents : des colonnes qui se remplissent, pas des tuiles de largeur figée. */
/* Cartes compactes, comme la maquette : on en voit beaucoup d'un coup, et un glisser-déposer d'un document
   vers un dossier ne demande pas de traverser la moitié de la page. */
.ws-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(12.8rem, 1fr)); grid-auto-rows: 19rem; gap: var(--edifice-spacing-12, 1.2rem); list-style: none; padding: 0; margin: 0; }
.ws-grid > li { min-width: 0; }
/* Hauteur fixe : une rangee de grille prend la taille de sa carte la plus haute, et un nom tres long
   suffirait a la faire tripler. */
.ws-card { position: relative; height: 100%; overflow: hidden; }
/* .card-image.medium du socle est un carré de 8rem : dans nos cartes il doit occuper toute la largeur. */
.ws-card .card-image { width: 100%; height: 8rem; display: flex; align-items: center; justify-content: center; overflow: hidden; }
.ws-card .card-image img { width: 100%; height: 100%; object-fit: cover; }
/* Case à cocher et pastille de type posées sur la vignette. */
.ws-card-check { position: absolute; top: .8rem; left: .8rem; z-index: 2; }
.ws-card-kind { position: absolute; top: .8rem; right: .8rem; z-index: 2; }
/* Le nom tient sur deux lignes puis se coupe : c'est la card-title du socle qui le fait, on la laisse faire
   (lui imposer une seule ligne couperait au milieu d'un mot, sans points de suspension). */
/* Deux lignes puis coupe. La .card-title du socle pose bien un clamp, mais il ne traverse pas l'element
   que l'on place dedans : on le repose ici. */
.ws-card-name { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; min-width: 0; overflow: hidden; }
/* Le nom d'un document n'est pas une invite à partir ailleurs : il prend la couleur du texte, pas celle des liens. */
.ws-card-name > a { color: inherit; text-decoration: none; }
.ws-card-name > a:hover, .ws-card-name > a:focus-visible { text-decoration: underline; }

/* Dans l'arborescence, l'emplacement courant se signale par le fond et la graisse que pose treeview :
   les noms de dossiers n'ont pas à porter en plus la couleur des liens. */
[data-workspace-tree] a, [data-nc-nav] a { color: inherit; text-decoration: none; }
[data-workspace-tree] a:hover, [data-nc-nav] a:hover,
[data-workspace-tree] a:focus-visible, [data-nc-nav] a:focus-visible { text-decoration: underline; }

/* Le thème masque le séparateur ::before des fils d'Ariane : on le pose nous-mêmes. */
.ws-crumb-sep { padding: 0 .4rem; color: var(--edifice-gray-700); }

/* En-tête d'une section (DOSSIERS, FICHIERS). */
.ws-section-title { font-size: 1.2rem; font-weight: 700; letter-spacing: .05em; text-transform: uppercase; }

/* Icône de type de fichier, dimensionnée par son conteneur et colorée par les utilitaires du thème. */
.ws-file-icon { width: 3.6rem; height: 3.6rem; }
.ws-file-icon-sm { width: 2rem; height: 2rem; }

/* Visionneuse en modale : le contenu occupe la hauteur disponible sans faire déborder la fenêtre. */
.ws-viewer-body { min-height: 40vh; display: flex; align-items: center; justify-content: center; }
.ws-viewer-body > img, .ws-viewer-body > video { max-width: 100%; max-height: 70vh; }
.ws-viewer-body > iframe { width: 100%; height: 70vh; border: 0; }

@media (max-width: 767px) {
  .ws-col-owner, .ws-col-date, .ws-col-size { display: none; }
  .ws-aside { flex: 1 1 100% !important; }
  .ws-main { flex: 1 1 100% !important; }
  [data-action-bar], [data-nc-action-bar] { flex-wrap: nowrap !important; overflow-x: auto; }
  [data-workspace-header] { flex-direction: column; align-items: stretch !important; }
  [data-workspace-toolbar], [data-nc-toolbar] { flex-wrap: wrap; }
  .ws-grid { grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr)); }
}
`;

export const WorkspaceStyle = () => <style data-responsive>{WORKSPACE_CSS}</style>;
