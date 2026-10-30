import { appsToExport, countDuplicateResources, countMainResources, exportableApps, quotaExceeded, readableSize, selectedImportSize, toggleAll } from "./archive";

describe("countMainResources / countDuplicateResources", () => {
  it("détaillé par ressource principale quand le rapport le permet", () => {
    const r = { mainResourceName: "blog", resourcesIdsMap: { blog: { a: 1, b: 2 }, post: { c: 3 } }, resourcesNumber: 9, duplicatesNumberMap: { blog: 1 }, duplicatesNumber: 4 };
    expect(countMainResources(r)).toBe(2);
    expect(countDuplicateResources(r)).toBe(1);
  });
  it("sinon le total du rapport", () => {
    expect(countMainResources({ resourcesNumber: 9 })).toBe(9);
    expect(countMainResources({ mainResourceName: "blog", resourcesIdsMap: {}, resourcesNumber: 5 })).toBe(5);
    expect(countDuplicateResources({ duplicatesNumber: 4 })).toBe(4);
    expect(countDuplicateResources({ mainResourceName: "blog", duplicatesNumberMap: { post: 2 }, duplicatesNumber: 7 })).toBe(7);
  });
});

describe("readableSize", () => {
  it("unités décimales", () => {
    expect(readableSize(999)).toEqual({ quantity: 999, unit: "import.bytes" });
    expect(readableSize(1_500_000)).toEqual({ quantity: 1.5, unit: "import.megabytes" });
    expect(readableSize(2_345_678_901)).toEqual({ quantity: 2.35, unit: "import.gigabytes" });
    expect(readableSize(0)).toEqual({ quantity: 0, unit: "import.bytes" });
  });
  it("au-delà de 1024 Ko on passe en Mo", () => {
    expect(readableSize(1_100_000).unit).toBe("import.megabytes");
    expect(readableSize(1_000_000).unit).toBe("import.kilobytes");
  });
});

describe("exportableApps", () => {
  const label = (k: string) => k.toUpperCase();
  it("garde les applications exportables, sans doublon, triées", () => {
    const mine = [{ prefix: "/wiki" }, { prefix: "/blog" }, { prefix: "/wiki" }, { prefix: "/inconnue" }, { displayName: "workspace" }];
    expect(exportableApps(mine, ["blog", "wiki", "workspace"], label)).toEqual(["blog", "wiki", "workspace"]);
  });
});

describe("appsToExport", () => {
  it("documents seulement, tout, ou la sélection", () => {
    expect(appsToExport("docsOnly", ["blog"], ["a", "b"])).toEqual(["workspace", "rack"]);
    expect(appsToExport("all", ["blog"], ["a", "b"])).toEqual(["a", "b"]);
    expect(appsToExport("apps", ["blog"], ["a", "b"])).toEqual(["blog"]);
  });
});

describe("sélection", () => {
  it("tout cocher bascule", () => {
    expect(toggleAll(["a", "b"], [])).toEqual(["a", "b"]);
    expect(toggleAll(["a", "b"], ["a"])).toEqual(["a", "b"]);
    expect(toggleAll(["a", "b"], ["a", "b"])).toEqual([]);
  });
  it("taille cumulée et dépassement de quota", () => {
    const apps = { blog: { size: 100 }, wiki: { size: 300 } };
    expect(selectedImportSize(apps, ["blog", "wiki"])).toBe(400);
    expect(selectedImportSize(apps, [])).toBe(0);
    expect(quotaExceeded(400, 399)).toBe(true);
    expect(quotaExceeded(400, 400)).toBe(false);
  });
});
