import { describe, expect, it } from "vitest";
import { canExpand, mergeFolders } from "./lazyFolders";
import { WorkspaceElement } from "./types";

const f = (_id: string, name: string, eParent: string | null = null): WorkspaceElement => ({ _id, name, eType: "folder", eParent, owner: "u" }) as WorkspaceElement;

describe("mergeFolders", () => {
  it("ajoute sans doublon et remplace un dossier reçu une seconde fois", () => {
    const merged = mergeFolders([f("a", "Cours"), f("b", "Archives")], [f("b", "Archives 2026"), f("c", "6e", "a")]);
    expect(merged.map((x) => x._id)).toEqual(["a", "b", "c"]);
    expect(merged.find((x) => x._id === "b")?.name).toBe("Archives 2026");
  });
  it("ne modifie pas la liste d'origine", () => {
    const base = [f("a", "Cours")];
    mergeFolders(base, [f("b", "x")]);
    expect(base).toHaveLength(1);
  });
});

describe("canExpand", () => {
  it("hors mode lazy : seulement s'il a des enfants", () => {
    expect(canExpand(false, false, false)).toBe(false);
    expect(canExpand(true, false, false)).toBe(true);
  });
  it("en mode lazy : dépliable tant que ses enfants ne sont pas chargés", () => {
    expect(canExpand(false, true, false)).toBe(true);
    expect(canExpand(false, true, true)).toBe(false);
    expect(canExpand(true, true, true)).toBe(true);
  });
});
