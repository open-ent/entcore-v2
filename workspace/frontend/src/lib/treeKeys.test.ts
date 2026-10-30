import { describe, expect, it } from "vitest";
import { treeMove, TreeRow } from "./treeKeys";

const rows: TreeRow[] = [
  { level: 1, expandable: false, expanded: false }, // 0 Mes documents
  { level: 2, expandable: true, expanded: true }, // 1 Cours (déplié)
  { level: 3, expandable: false, expanded: false }, // 2 6e
  { level: 2, expandable: true, expanded: false }, // 3 Archives (plié)
  { level: 1, expandable: false, expanded: false }, // 4 Documents partagés
];

describe("treeMove", () => {
  it("flèches haut/bas, Début et Fin restent dans les bornes", () => {
    expect(treeMove("ArrowDown", rows, 0)).toEqual({ focus: 1 });
    expect(treeMove("ArrowDown", rows, 4)).toBeNull();
    expect(treeMove("ArrowUp", rows, 2)).toEqual({ focus: 1 });
    expect(treeMove("ArrowUp", rows, 0)).toBeNull();
    expect(treeMove("Home", rows, 3)).toEqual({ focus: 0 });
    expect(treeMove("End", rows, 3)).toEqual({ focus: 4 });
  });

  it("→ déplie un dossier plié, sinon entre dans le premier sous-dossier", () => {
    expect(treeMove("ArrowRight", rows, 3)).toEqual({ toggle: "open" });
    expect(treeMove("ArrowRight", rows, 1)).toEqual({ focus: 2 });
    expect(treeMove("ArrowRight", rows, 2)).toBeNull();
  });

  it("← replie un dossier déplié, sinon remonte au parent", () => {
    expect(treeMove("ArrowLeft", rows, 1)).toEqual({ toggle: "close" });
    expect(treeMove("ArrowLeft", rows, 2)).toEqual({ focus: 1 });
    expect(treeMove("ArrowLeft", rows, 3)).toEqual({ focus: 0 });
    expect(treeMove("ArrowLeft", rows, 0)).toBeNull();
  });

  it("touche inconnue ou position hors liste : rien", () => {
    expect(treeMove("a", rows, 1)).toBeNull();
    expect(treeMove("ArrowDown", rows, 9)).toBeNull();
  });
});
