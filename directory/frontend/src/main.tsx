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
