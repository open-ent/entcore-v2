/**
 * Adaptation aux petits écrans (moins de 768 px), comme l'ancienne interface (`zero-mobile`) : propriétaire, date et poids disparaissent
 * des listes, la colonne de gauche passe au-dessus du contenu, les barres d'actions défilent au lieu de déborder.
 */
export const RESPONSIVE_CSS = `
@media (max-width: 767px) {
  .ws-col-owner, .ws-col-date, .ws-col-size { display: none; }
  .ws-aside { flex: 1 1 100% !important; }
  .ws-main { flex: 1 1 100% !important; }
  [data-action-bar], [data-nc-action-bar] { flex-wrap: nowrap !important; overflow-x: auto; }
  [data-workspace-header] { flex-direction: column; align-items: stretch !important; }
  [data-workspace-toolbar], [data-nc-toolbar] { flex-wrap: wrap; }
}
`;

export const ResponsiveStyle = () => <style data-responsive>{RESPONSIVE_CSS}</style>;
