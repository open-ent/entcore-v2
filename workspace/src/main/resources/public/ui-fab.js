/*
 * Bascule PERMANENTE entre l'ancienne (AngularJS) et la nouvelle (React) interface, pour tout module à
 * double interface. Fichier GÉNÉRIQUE, identique dans chaque module : il se repère à l'adresse de la page
 * (`/diary`, `/edt`…) et se crée lui-même — une simple balise <script> suffit dans la vue.
 *
 * Règles :
 *  - bouton flottant en bas à droite, sans compteur : on peut aller et venir autant qu'on veut ;
 *  - AUCUNE préférence écrite (dérogation d'URL `?ui=` seule) ;
 *  - toujours affiché, même quand l'usager a posé une préférence dans « Paramètres du compte » (clé `<app>Ui`) :
 *    la dérogation d'URL `?ui=` passe avant la préférence, on peut donc revenir à l'autre interface à tout moment ;
 *  - laisse la place au bandeau d'invitation / de retour d'expérience du module tant qu'il est affiché
 *    (élément dont la classe se termine par `-ui-switch`, ou `[data-ui-banner]`), puis prend le relais.
 * Même logique dans le socle React (Layout/UiSwitchFab).
 */
(function () {
  'use strict';

  // Modules à double interface (adresse). La clé de préférence du compte est `<adresse>Ui` sans caractère non
  // alphanumérique (entcore les retire) : `diary` → `diaryUi`, `statistics-presences` → `statisticspresencesUi`.
  var APPS = ['workspace', 'diary', 'edt', 'calendar', 'exercizer', 'competences', 'forum', 'pages', 'stats', 'rbs', 'searchengine',
    'communities', 'timelinegenerator', 'incidents', 'massmailing', 'statistics-presences'];
  var app = (window.location.pathname.split('/')[1] || '');
  if (APPS.indexOf(app) < 0 || document.getElementById('openent-ui-fab')) return;

  var fr = /^fr/i.test(document.documentElement.lang || navigator.language || 'fr');
  var T = fr
    ? { label: "Version de l'interface", next: 'Nouvelle version', prev: 'Ancienne version', current: 'actuelle' }
    : { label: 'Interface version', next: 'New version', prev: 'Previous version', current: 'current' };
  var BANNERS = '[class*="-ui-switch"], [data-ui-banner]';

  // Un bandeau est « affiché » s'il n'est ni masqué ni sans boîte : `offsetParent` vaut null pour TOUT élément
  // en position fixe (c'est le cas des bandeaux), il ne peut donc pas servir de test.
  function shown(el) {
    if (el.hidden || el.id === 'openent-ui-fab') return false;
    var style = window.getComputedStyle(el);
    return style.display !== 'none' && style.visibility !== 'hidden' && el.getClientRects().length > 0;
  }

  function bannerVisible() {
    var list = document.querySelectorAll(BANNERS);
    for (var i = 0; i < list.length; i++) { if (shown(list[i])) return true; }
    return false;
  }

  // Distance entre le bas de la fenêtre et le haut du bandeau du module (fixe lui aussi, en bas à droite) : le bouton se pose juste au-dessus
  // au lieu de disparaître derrière lui. 0 sans bandeau.
  function bannerOffset() {
    var list = document.querySelectorAll(BANNERS), top = null;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!shown(el) || window.getComputedStyle(el).position !== 'fixed') continue;
      var t = el.getBoundingClientRect().top;
      if (top === null || t < top) top = t;
    }
    return top === null ? 0 : Math.max(0, window.innerHeight - top);
  }

  function build() {
    // Interface actuellement affichée : AngularJS charge `window.angular` (ng-app.js), la page React non. Le menu s'y adapte.
    var current = (typeof window.angular !== 'undefined') ? 'angular' : 'react';
    var style = document.createElement('style');
    style.textContent =
      '#openent-ui-fab{position:fixed;right:16px;bottom:16px;z-index:9500;font:14px/1.3 sans-serif}' +
      '#openent-ui-fab[hidden]{display:none}' +
      '#openent-ui-fab>button{border:0;border-radius:20px;padding:8px 14px;background:#243242;color:#fff;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.3)}' +
      '#openent-ui-fab .menu{position:absolute;right:0;bottom:44px;min-width:200px;background:#fff;color:#243242;border-radius:8px;box-shadow:0 4px 16px rgba(0,0,0,.3);padding:6px}' +
      '#openent-ui-fab .menu[hidden]{display:none}' +
      '#openent-ui-fab .menu button{display:block;width:100%;text-align:left;border:0;background:transparent;padding:8px 10px;border-radius:6px;cursor:pointer;color:inherit;font:inherit}' +
      '#openent-ui-fab .menu button:hover{background:#eef1f4}' +
      '#openent-ui-fab .menu button[aria-current="true"]{font-weight:700}';
    document.head.appendChild(style);
    var fab = document.createElement('div');
    fab.id = 'openent-ui-fab';
    fab.hidden = true;
    fab.innerHTML =
      '<div class="menu" role="menu" hidden>' +
      '<button type="button" role="menuitem" data-ui="react"' + (current === 'react' ? ' aria-current="true"' : '') + '>' + T.next + (current === 'react' ? ' (' + T.current + ')' : '') + '</button>' +
      '<button type="button" role="menuitem" data-ui="angular"' + (current === 'angular' ? ' aria-current="true"' : '') + '>' + T.prev + (current === 'angular' ? ' (' + T.current + ')' : '') + '</button>' +
      '</div>' +
      '<button type="button" aria-haspopup="menu">⇄ ' + T.label + '</button>';
    var menu = fab.querySelector('.menu');
    // L'interface affichée se relit à l'ouverture du menu : `window.angular` peut ne pas être encore chargé quand ce script s'exécute.
    function sync() {
      current = (typeof window.angular !== 'undefined') ? 'angular' : 'react';
      Array.prototype.forEach.call(menu.querySelectorAll('[data-ui]'), function (item) {
        var ui = item.getAttribute('data-ui'), is = ui === current;
        if (is) item.setAttribute('aria-current', 'true'); else item.removeAttribute('aria-current');
        item.textContent = (ui === 'react' ? T.next : T.prev) + (is ? ' (' + T.current + ')' : '');
      });
    }
    fab.querySelector('[aria-haspopup]').addEventListener('click', function () { sync(); menu.hidden = !menu.hidden; });
    Array.prototype.forEach.call(menu.querySelectorAll('[data-ui]'), function (item) {
      item.addEventListener('click', function () {
        var ui = item.getAttribute('data-ui');
        if (ui === current) { menu.hidden = true; return; }
        window.location.href = window.location.pathname + '?ui=' + ui;
      });
    });
    document.body.appendChild(fab);
    return fab;
  }

  Promise.resolve().then(function () {
    var fab = build();
    // N'écrit que si l'état change : réaffecter `hidden` avec la même valeur crée tout de même un enregistrement de
    var refresh = function () {
      // pas de doublon : si le socle React affiche déjà sa propre bascule (`[data-ui-fab]`), celle-ci s'efface ; sinon elle reste TOUJOURS visible
      var h = !!document.querySelector('[data-ui-fab]');
      if (fab.hidden !== h) fab.hidden = h;
      var offset = bannerOffset();
      var bottom = offset ? (offset + 8) + 'px' : '16px';
      if (fab.style.bottom !== bottom) fab.style.bottom = bottom;
    };
    // Le bandeau du module apparaît après sa propre lecture de la préférence : on laisse un instant puis on suit ses changements.
    setTimeout(function () {
      refresh();
      new MutationObserver(refresh).observe(document.body, { attributes: true, attributeFilter: ['hidden', 'class', 'style'], subtree: true, childList: true });
    }, 1200);
  });
})();
