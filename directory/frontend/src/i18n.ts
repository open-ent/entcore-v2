import i18n from "i18next";
import Backend from "i18next-http-backend";
import { initReactI18next } from "react-i18next";

// Clés plates (« directory.title »), traduites par le serveur : /i18n (commun) et /directory/i18n.
i18n
  .use(Backend)
  .use(initReactI18next)
  .init({
    backend: {
      loadPath: (_lngs: string[], namespaces: string[]) =>
        namespaces.map((ns) => (ns === "common" ? "/i18n" : `/${ns}/i18n`)),
      parse: (data: string) => JSON.parse(data),
    },
    defaultNS: "common",
    ns: ["common", "directory"],
    // une clé absente de /directory/i18n se cherche dans le commun
    fallbackNS: "common",
    fallbackLng: "fr",
    lng: "fr",
    keySeparator: false,
    nsSeparator: false,
    interpolation: { escapeValue: false, prefix: "[[", suffix: "]]" },
    debug: false,
  });

export default i18n;
