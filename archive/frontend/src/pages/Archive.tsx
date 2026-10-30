import { AppHeader, Layout } from "@open-ent/react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ExportTab } from "../components/ExportTab";
import { ImportTab } from "../components/ImportTab";

type Tab = "export" | "import";

/** Archivage : exporter ses données dans un fichier, ou importer un fichier exporté (deux onglets, comme l'ancienne interface). */
export const Archive = () => {
  const { t } = useTranslation("archive");
  const [tab, setTab] = useState<Tab>("export");
  return (
    <Layout>
      <AppHeader>
        <h1 className="h2" data-archive-title>
          {t("archive.title")}
        </h1>
      </AppHeader>
      <div role="tablist" className="d-flex gap-16 mb-24" style={{ borderBottom: "1px solid #dee2e6" }}>
        {(["export", "import"] as const).map((id) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            className="btn btn-link"
            style={{ borderBottom: tab === id ? "3px solid #ffb400" : "3px solid transparent", fontWeight: tab === id ? 700 : 400, borderRadius: 0, color: "inherit" }}
            onClick={() => setTab(id)}
            data-archive-tab={id}
          >
            {t(id === "export" ? "archive.export" : "archive.import")}
          </button>
        ))}
      </div>
      {tab === "export" ? <ExportTab /> : <ImportTab />}
    </Layout>
  );
};
