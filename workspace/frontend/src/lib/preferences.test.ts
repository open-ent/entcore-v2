import { nextSort, sortKeyOf, SORT_FIELD, viewOf } from "./preferences";

describe("préférence d'affichage et de tri", () => {
  it("relit les champs de tri de l'ancienne interface", () => {
    expect(sortKeyOf("metadata.size")).toBe("size");
    expect(sortKeyOf("ownerName")).toBe("owner");
    expect(sortKeyOf("modified")).toBe("modified");
    expect(sortKeyOf("n'importe quoi")).toBeUndefined();
    expect(sortKeyOf(undefined)).toBeUndefined();
    expect(SORT_FIELD.name).toBe("name");
  });
  it("n'affiche en liste que si la préférence le dit", () => {
    expect(viewOf({ view: "list" })).toBe("list");
    expect(viewOf({ view: "carousel" })).toBe("icons");
    expect(viewOf(undefined)).toBe("icons");
  });
  it("le même critère inverse le sens, un autre repart en croissant", () => {
    expect(nextSort({ key: "name", desc: false }, "name")).toEqual({ key: "name", desc: true });
    expect(nextSort({ key: "name", desc: true }, "name")).toEqual({ key: "name", desc: false });
    expect(nextSort({ key: "name", desc: true }, "size")).toEqual({ key: "size", desc: false });
  });
});
