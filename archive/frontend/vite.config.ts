/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";
import { defineConfig } from "vite";

/**
 * Bundle unique (IIFE) servi par `GET /archive` quand l'interface React est choisie
 * (vue `archive-react.html`). Il est écrit sous `public/react/` du module.
 */
export default defineConfig({
  base: "/archive/public/react/",
  root: __dirname,
  cacheDir: "./node_modules/.vite/archive",
  resolve: {
    // Instance unique des libs partagées (sinon odeServices n'est pas initialisé).
    dedupe: [
      "react",
      "react-dom",
      "@tanstack/react-query",
      "react-i18next",
      "i18next",
      "@open-ent/client",
      "@open-ent/react",
      "@open-ent/bootstrap",
      "@open-ent/utilities",
    ],
    alias: { "~": resolve(__dirname, "src") },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    assetsDir: "assets",
    rollupOptions: {
      input: resolve(__dirname, "index.html"),
      output: {
        format: "iife",
        entryFileNames: "archive.js",
        inlineDynamicImports: true,
      },
    },
  },
  server: {
    port: 4201,
    host: "localhost",
    proxy: { "^/(?!src|node_modules|@)": { target: "http://localhost:8090", changeOrigin: false } },
  },
  plugins: [react()],
  test: {
    watch: false,
    globals: true,
    environment: "jsdom",
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    setupFiles: ["./src/test-setup.ts"],
  },
});
