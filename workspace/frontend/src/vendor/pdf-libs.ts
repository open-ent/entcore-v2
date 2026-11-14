/**
 * Bibliothèques de l'éditeur PDF, construites à part (vite.pdf-libs.config.ts) en
 * `public/react/pdf-libs.js` et chargées seulement à l'ouverture de l'éditeur : elles pèsent plus
 * d'un mégaoctet, que l'espace documentaire n'a pas à télécharger à chaque visite.
 * Aucune dépendance à React : pas de risque d'instance dupliquée.
 */
export * as pdfLib from "pdf-lib";
export * as pdfjs from "pdfjs-dist";
