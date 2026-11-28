/**
 * Styles de l'annuaire, en « carnet d'adresses » : entrées rangées sous de grandes initiales, profil codé par couleur.
 * Injectés par une balise <style> : le bundle IIFE n'a pas de feuille CSS chargée par la vue `annuaire-react.html`.
 * Tout est préfixé par `.annuaire` pour ne pas déborder sur le reste du socle.
 */
export const ANNUAIRE_CSS = `
.annuaire {
  --ann-ink: #1f2d3a;
  --ann-muted: #5c6b78;
  --ann-line: #e3e9ee;
  --ann-wash: #eef7fc;
  --ann-brand: var(--edifice-blue-500, #2a9cc8);
  --ann-brand-deep: var(--edifice-blue-900, #005a8a);
  --ann-teacher: #005a8a;
  --ann-student: #058f7d;
  --ann-relative: #823aa0;
  --ann-personnel: #c0600a;
  --ann-guest: #6b6b6b;
  color: var(--ann-ink);
}
.annuaire :focus-visible { outline: 3px solid var(--ann-brand); outline-offset: 2px; }

/* Onglets */
.annuaire .ann-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--ann-line); margin-bottom: 20px; overflow-x: auto; }
.annuaire .ann-tab {
  background: none; border: 0; border-bottom: 3px solid transparent; margin-bottom: -1px;
  padding: 10px 16px; font-size: 16px; color: var(--ann-muted); white-space: nowrap; cursor: pointer;
}
.annuaire .ann-tab:hover { color: var(--ann-ink); }
.annuaire .ann-tab[aria-selected="true"] { color: var(--ann-brand-deep); border-bottom-color: var(--ann-brand); font-weight: 700; }

/* Panneau de recherche */
.annuaire .ann-search { background: var(--ann-wash); border-radius: 12px; padding: 20px; margin-bottom: 28px; }
.annuaire .ann-query { display: flex; gap: 8px; }
.annuaire .ann-query-field { position: relative; flex: 1; }
.annuaire .ann-query-field svg { position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: var(--ann-muted); pointer-events: none; }
.annuaire .ann-query-field input {
  width: 100%; height: 48px; padding: 0 14px 0 44px; font-size: 17px;
  border: 1px solid #c9d6df; border-radius: 8px; background: #fff; color: var(--ann-ink);
}
.annuaire .ann-query-field input:focus { border-color: var(--ann-brand); outline: none; box-shadow: 0 0 0 3px rgba(42,156,200,.25); }
.annuaire .ann-query button { height: 48px; padding: 0 24px; }
.annuaire .ann-filters { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 16px 32px; margin-top: 16px; }
.annuaire .ann-filters fieldset { border: 0; margin: 0; padding: 0; min-width: 0; }
.annuaire .ann-filter-label { display: block; font-size: 14px; font-weight: 600; color: var(--ann-muted); margin-bottom: 6px; padding: 0; }
.annuaire .ann-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.annuaire .ann-chip {
  display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px 6px 10px;
  border: 1px solid #c9d6df; border-radius: 999px; background: #fff; cursor: pointer; font-size: 15px; user-select: none;
}
.annuaire .ann-chip input { accent-color: var(--chip, var(--ann-brand)); margin: 0; width: 16px; height: 16px; cursor: pointer; }
.annuaire .ann-chip:has(input:checked) { border-color: var(--chip); background: color-mix(in srgb, var(--chip) 10%, #fff); color: var(--chip); font-weight: 600; }
.annuaire .ann-chip:has(input:focus-visible) { outline: 3px solid var(--ann-brand); outline-offset: 2px; }
.annuaire .ann-select { display: flex; flex-direction: column; min-width: 220px; }
.annuaire .ann-select select { height: 40px; border-radius: 8px; border-color: #c9d6df; }

/* Couleur par profil */
.annuaire [data-profile="Teacher"] { --chip: var(--ann-teacher); }
.annuaire [data-profile="Student"] { --chip: var(--ann-student); }
.annuaire [data-profile="Relative"] { --chip: var(--ann-relative); }
.annuaire [data-profile="Personnel"] { --chip: var(--ann-personnel); }
.annuaire [data-profile="Guest"] { --chip: var(--ann-guest); }

/* En-tête des résultats + réglette A–Z */
.annuaire .ann-results-head {
  position: sticky; top: 0; z-index: 2; background: var(--edifice-body-bg, #fff);
  display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 16px;
  padding: 8px 0; margin-bottom: 4px; border-bottom: 1px solid var(--ann-line);
}
.annuaire .ann-count { font-size: 15px; color: var(--ann-muted); margin: 0; }
.annuaire .ann-count strong { color: var(--ann-ink); }
.annuaire .ann-rail { display: flex; flex-wrap: wrap; gap: 2px; list-style: none; margin: 0; padding: 0; }
.annuaire .ann-rail a {
  display: inline-flex; align-items: center; justify-content: center; min-width: 28px; height: 28px; border-radius: 6px;
  font-weight: 700; font-size: 14px; color: var(--ann-brand-deep); text-decoration: none;
}
.annuaire .ann-rail a:hover { background: var(--ann-wash); }

/* Sections du carnet */
.annuaire .ann-section { display: grid; grid-template-columns: 64px 1fr; gap: 0 8px; padding: 12px 0; border-bottom: 1px solid var(--ann-line); scroll-margin-top: 56px; }
.annuaire .ann-letter {
  margin: 0; font-size: 44px; line-height: 1; font-weight: 300; color: var(--ann-brand);
  position: sticky; top: 56px; align-self: start; padding-top: 6px;
}
.annuaire .ann-list { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 2px 16px; }

/* Entrée (personne ou groupe) */
.annuaire .ann-entry {
  display: flex; align-items: center; gap: 12px; width: 100%; min-width: 0; padding: 8px 10px;
  background: none; border: 0; border-radius: 8px; text-align: left; color: inherit; cursor: pointer;
}
.annuaire .ann-entry:hover { background: var(--ann-wash); }
.annuaire .ann-avatar {
  flex: none; width: 44px; height: 44px; border-radius: 50%; object-fit: cover; background: #f2f2f2;
  box-shadow: 0 0 0 2px #fff, 0 0 0 4px var(--chip, var(--ann-line));
}
.annuaire .ann-entry-text { min-width: 0; display: flex; flex-direction: column; }
.annuaire .ann-entry-text strong { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.annuaire .ann-entry-text small { display: inline-flex; align-items: center; gap: 6px; color: var(--ann-muted); font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.annuaire .ann-entry-text small::before { content: ""; flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--chip, var(--ann-line)); }
.annuaire .ann-group-mark {
  flex: none; width: 44px; height: 44px; border-radius: 10px; display: inline-flex; align-items: center; justify-content: center;
  background: var(--ann-wash); color: var(--ann-brand-deep);
}

/* États vides */
.annuaire .ann-empty { text-align: center; color: var(--ann-muted); padding: 48px 16px; max-width: 520px; margin: 0 auto; }
.annuaire .ann-empty h2 { font-size: 20px; color: var(--ann-ink); margin-bottom: 8px; }
.annuaire .ann-empty svg { color: var(--ann-brand); margin-bottom: 16px; opacity: .8; }

/* Fiche */
.annuaire .ann-back { background: none; border: 0; padding: 4px 0; margin-bottom: 16px; color: var(--ann-brand-deep); font-weight: 600; cursor: pointer; }
.annuaire .ann-back:hover { text-decoration: underline; }
.annuaire .ann-card-head { display: flex; align-items: center; gap: 24px; padding: 24px; background: var(--ann-wash); border-radius: 12px; margin-bottom: 24px; }
.annuaire .ann-card-head .ann-avatar { width: 96px; height: 96px; box-shadow: 0 0 0 3px #fff, 0 0 0 6px var(--chip, var(--ann-line)); }
.annuaire .ann-card-head h2 { font-size: 28px; margin: 0 0 4px; }
.annuaire .ann-badge { display: inline-flex; align-items: center; gap: 6px; padding: 2px 10px; border-radius: 999px; background: #fff; color: var(--chip, var(--ann-muted)); font-weight: 600; font-size: 14px; }
.annuaire .ann-badge::before { content: ""; width: 8px; height: 8px; border-radius: 50%; background: var(--chip, var(--ann-line)); }
.annuaire .ann-subjects { color: var(--ann-muted); margin-top: 6px; }
.annuaire .ann-motto { font-style: italic; margin: 10px 0 0; color: var(--ann-ink); }
.annuaire .ann-card-body { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 24px 40px; }
.annuaire .ann-card-body h3 { font-size: 16px; font-weight: 700; color: var(--ann-muted); margin: 0 0 10px; }
.annuaire .ann-card-body ul { list-style: none; padding: 0; margin: 0; }
.annuaire .ann-card-body li { padding: 8px 0; border-bottom: 1px solid var(--ann-line); }
.annuaire .ann-card-body li span { display: block; color: var(--ann-muted); font-size: 14px; }
.annuaire .ann-card-body dl { margin: 0; }
.annuaire .ann-card-body dl > div { display: grid; grid-template-columns: 150px 1fr; gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--ann-line); }
.annuaire .ann-card-body dt { font-weight: 600; color: var(--ann-muted); }
.annuaire .ann-card-body dd { margin: 0; overflow-wrap: anywhere; }

@media (max-width: 640px) {
  .annuaire .ann-search { padding: 14px; }
  .annuaire .ann-query button { padding: 0 14px; }
  .annuaire .ann-select { min-width: 0; flex: 1 1 100%; }
  .annuaire .ann-section { grid-template-columns: 1fr; }
  .annuaire .ann-letter { position: static; font-size: 28px; padding: 0 0 4px; }
  .annuaire .ann-results-head { position: static; }
  .annuaire .ann-card-head { flex-direction: column; text-align: center; padding: 20px 16px; }
  .annuaire .ann-card-body dl > div { grid-template-columns: 1fr; gap: 2px; }
}
@media (prefers-reduced-motion: reduce) { .annuaire * { scroll-behavior: auto !important; } }
`;
