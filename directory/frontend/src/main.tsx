import { EdificeThemeProvider } from "@open-ent/react";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { I18nextProvider } from "react-i18next";
import i18n from "./i18n";
import { Providers } from "./providers";
import { Annuaire } from "./pages/Annuaire";
import { MonCompte } from "./pages/MonCompte";

// Une seule IHM servie par deux vues : /userbook/mon-compte (Mon compte) et /userbook/annuaire (Annuaire).
const isAnnuaire = window.location.pathname.endsWith("/annuaire");

// Le thème de la plateforme rend les `.btn-link` quasi invisibles (même couleur que le fond) : ils reprennent la couleur du texte, soulignés.
const style = document.createElement("style");
// `.account-slot` : colonnes fixes (visibilité, modifier) pour aligner les actions des lignes de « Mon profil ».
style.textContent =
  ".btn-link{color:inherit !important;text-decoration:underline}" +
  ".account-value{flex:1 1 16rem;min-width:0}" +
  ".account-slot{flex:0 0 9.5rem;display:flex;justify-content:flex-end}" +
  "@media (max-width:767.98px){.account-slot{flex-basis:auto}.account-slot:empty{display:none}}";
document.head.appendChild(style);

const root = document.getElementById("root");
if (root) {
  createRoot(root).render(
    <StrictMode>
      <Providers>
        <I18nextProvider i18n={i18n}>
          <EdificeThemeProvider>{isAnnuaire ? <Annuaire /> : <MonCompte />}</EdificeThemeProvider>
        </I18nextProvider>
      </Providers>
    </StrictMode>,
  );
}
