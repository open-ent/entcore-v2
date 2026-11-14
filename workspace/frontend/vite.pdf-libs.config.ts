import { resolve } from "node:path";
import { defineConfig } from "vite";

/**
 * Fichier annexe de l'éditeur PDF : pdf-lib et pdf.js en module ES, `dist/pdf-libs.js`, chargé à la
 * demande par src/lib/pdfLibs.ts. Construit après le bundle principal (même dossier dist, non vidé).
 */
export default defineConfig({
  root: __dirname,
  cacheDir: "./node_modules/.vite/workspace-pdf-libs",
  build: {
    outDir: "dist",
    emptyOutDir: false,
    copyPublicDir: false,
    lib: {
      entry: resolve(__dirname, "src/vendor/pdf-libs.ts"),
      formats: ["es"],
      fileName: () => "pdf-libs.js",
    },
    rollupOptions: { output: { inlineDynamicImports: true } },
  },
});
