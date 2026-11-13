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
.ws-selected { outline: 2px solid var(--openent-primary); outline-offset: 1px; }
.ws-drop-target { outline: 2px dashed var(--openent-primary); outline-offset: 2px; }

/* Grille des dossiers et des documents : des colonnes qui se remplissent, pas des tuiles de largeur figée. */
/* Cartes compactes, comme la maquette : on en voit beaucoup d'un coup, et un glisser-déposer d'un document
   vers un dossier ne demande pas de traverser la moitié de la page. */
.ws-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(12.8rem, 1fr)); grid-auto-rows: 19rem; gap: 1.2rem; list-style: none; padding: 0; margin: 0; }
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

/* Arborescence (espace documentaire et NextCloud). Chaque ligne : case chevron, case icône, nom ; les cases ont une
   largeur fixe pour que tout s'aligne, chevron ou non. Une seule taille de texte à tous les niveaux (le socle réduit
   les sous-niveaux à 1,4rem). Les sous-dossiers sont rattachés par un filet passant sous le chevron du parent, et
   leur chevron tombe à l'aplomb de l'icône du parent (0,8 + 1 = 1,8rem ; 1,8 + 0,8 + 0,8 = 0,8 + 2 + 0,6 = 3,4rem). */
.ws-tree-row { gap: .6rem; padding: .6rem .8rem; min-height: 3.6rem; font-size: 1.5rem; line-height: 1.3; border-radius: .8rem; }
.ws-tree-row > a { flex: 1 1 auto; min-width: 0; color: inherit; text-decoration: none; }
.ws-tree-row > a:focus-visible { text-decoration: underline; }
.ws-tree-chevron { flex: 0 0 2rem; width: 2rem; height: 2rem; padding: 0; border: 0; border-radius: .4rem; background: transparent; color: var(--openent-gray-700); display: inline-flex; align-items: center; justify-content: center; }
button.ws-tree-chevron:hover { background: var(--openent-gray-400); color: var(--openent-gray-900); }
.ws-tree-chevron > svg { transition: transform .15s ease; }
.ws-tree-chevron[data-expanded] > svg { transform: rotate(90deg); }
.ws-tree-icon { flex: 0 0 2rem; width: 2rem; height: 2rem; display: inline-flex; align-items: center; justify-content: center; }
.ws-tree-icon > svg { width: 2rem; height: 2rem; }
.treeview.treeview [role=tree] li + li { padding-top: .2rem; }
.treeview.treeview .ws-tree-group-start { border-top: 1px solid var(--openent-gray-300); margin-top: .8rem; padding-top: .8rem !important; }
.treeview.treeview [role=group] { font-size: inherit; margin-left: 1.8rem; padding-left: .8rem; padding-top: .2rem; border-left: 1px solid var(--openent-gray-300); }
.treeview.treeview [role=group] > [role=treeitem] { margin-left: 0; }
.treeview.treeview [role=group] > [role=treeitem] + [role=treeitem] { padding-top: .2rem; }
/* Emplacement courant : le sélecteur du socle vise une ligne enveloppée d'un div de plus, on le pose sur la nôtre. */
.treeview.treeview [role=treeitem][aria-selected=true] > .ws-tree-row { font-weight: 700; background-color: var(--openent-selected-background-color); }
.treeview.treeview [role=treeitem][aria-selected=true] > .ws-tree-row .ws-tree-icon { color: var(--openent-secondary) !important; }

/* Barre au-dessus de la liste : recherche, tri et choix de la vue ont la même hauteur (4,2rem, celle du champ de
   recherche du socle) et la même taille de texte ; le socle donne 5rem au tri et 3,2rem, en 1,4rem, au choix de vue. */
.ws-view-controls .dropdown-toggle { height: 4.2rem; padding-top: 0; padding-bottom: 0; display: inline-flex; align-items: center; }
.ws-view-controls .ant-segmented { height: 4.2rem; padding: .3rem; font-size: 1.6rem; display: inline-flex; align-items: center; border-radius: .8rem; }
.ws-view-controls .ant-segmented-group { height: 100%; }
.ws-view-controls .ant-segmented-item { display: inline-flex; align-items: center; border-radius: .6rem; }
.ws-view-controls .ant-segmented-item-label { min-height: 3.6rem; line-height: 3.6rem; padding: 0 1rem; display: inline-flex; align-items: center; }
.ws-view-controls .ant-segmented-item-thumb, .ws-view-controls .ant-segmented-thumb { border-radius: .6rem; }
.ws-view-controls .ant-segmented-item-icon { display: inline-flex; margin: 0; }
/* Icônes seules : le libellé est masqué à l'écran mais reste lu par les lecteurs d'écran (et sert d'infobulle). */
.ws-view-controls .ant-segmented-item-icon + span { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }

/* Le thème masque le séparateur ::before des fils d'Ariane : on le pose nous-mêmes. */
.ws-crumb-sep { padding: 0 .4rem; color: var(--openent-gray-700); }

/* En-tête d'une section (DOSSIERS, FICHIERS). */
.ws-section-title { font-size: 1.2rem; font-weight: 700; letter-spacing: .05em; text-transform: uppercase; }

/* Icône de type de fichier, dimensionnée par son conteneur et colorée par les utilitaires du thème. */
.ws-file-icon { width: 3.6rem; height: 3.6rem; }
.ws-file-icon-sm { width: 2rem; height: 2rem; }

/* Visionneuse en modale : le contenu occupe la hauteur disponible sans faire déborder la fenêtre. */
.ws-viewer-body { min-height: 40vh; display: flex; align-items: center; justify-content: center; }
.ws-viewer-body > img, .ws-viewer-body > video { max-width: 100%; max-height: 70vh; }
.ws-viewer-body > iframe { width: 100%; height: 70vh; border: 0; }

/* Barre de sélection : la carte du socle masque ce qui dépasse, ce qui rognait les menus déroulants
   longs (« Transformer », 8 entrées). Sur mobile, la règle plus bas la fait défiler horizontalement. */
[data-action-bar], [data-nc-action-bar] { overflow: visible !important; }

@media (max-width: 767px) {
  .ws-col-owner, .ws-col-date, .ws-col-size { display: none; }
  .ws-aside { flex: 1 1 100% !important; }
  .ws-main { flex: 1 1 100% !important; }
  [data-action-bar], [data-nc-action-bar] { flex-wrap: nowrap !important; overflow-x: auto !important; }
  [data-workspace-header] { flex-direction: column; align-items: stretch !important; }
  [data-workspace-toolbar], [data-nc-toolbar] { flex-wrap: wrap; }
  .ws-grid { grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr)); }
}
`;

export const WorkspaceStyle = () => <style data-responsive>{WORKSPACE_CSS}</style>;
