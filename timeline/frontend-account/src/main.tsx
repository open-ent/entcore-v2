import { EdificeThemeProvider } from "@open-ent/react";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { I18nextProvider } from "react-i18next";
import i18n from "./i18n";
import { Providers } from "./providers";
import { History } from "./pages/History";
import { Preferences } from "./pages/Preferences";

// Une seule IHM servie par deux vues : /timeline/preferencesView (notifications externes) et /timeline/historyView (historique).
const isHistory = window.location.pathname.endsWith("/historyView");

// Le thème de la plateforme rend les `.btn-link` quasi invisibles (même couleur que le fond) : ils reprennent la couleur du texte, soulignés.
const style = document.createElement("style");
style.textContent = ".btn-link{color:inherit !important;text-decoration:underline}";
document.head.appendChild(style);

const root = document.getElementById("root");
if (root) {
  createRoot(root).render(
    <StrictMode>
      <Providers>
        <I18nextProvider i18n={i18n}>
          <EdificeThemeProvider>
            {isHistory ? <History /> : <Preferences />}
          </EdificeThemeProvider>
        </I18nextProvider>
      </Providers>
    </StrictMode>,
  );
}
