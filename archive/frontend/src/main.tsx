import { EdificeThemeProvider } from "@open-ent/react";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { I18nextProvider } from "react-i18next";
import i18n from "./i18n";
import { Providers } from "./providers";
import { Archive } from "./pages/Archive";

// Le thème openent-bootstrap est chargé à l'exécution par <link> dans la vue (comme l'interface AngularJS).
const root = document.getElementById("root");
if (root) {
  createRoot(root).render(
    <StrictMode>
      <Providers>
        <I18nextProvider i18n={i18n}>
          <EdificeThemeProvider>
            <Archive />
          </EdificeThemeProvider>
        </I18nextProvider>
      </Providers>
    </StrictMode>,
  );
}
