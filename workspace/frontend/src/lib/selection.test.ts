import { isTypingTarget, rangeIds, shortcutOf } from "./selection";

describe("sélection", () => {
  const ids = ["a", "b", "c", "d"];
  it("Maj + clic sélectionne la plage, dans les deux sens", () => {
    expect(rangeIds(ids, "b", "d")).toEqual(["b", "c", "d"]);
    expect(rangeIds(ids, "d", "b")).toEqual(["b", "c", "d"]);
    expect(rangeIds(ids, "c", "c")).toEqual(["c"]);
  });
  it("sans point de départ, seul l'élément cliqué est retenu", () => {
    expect(rangeIds(ids, undefined, "c")).toEqual(["c"]);
    expect(rangeIds(ids, "disparu", "c")).toEqual(["c"]);
    expect(rangeIds(ids, "a", "inconnu")).toEqual([]);
  });
  it("reconnaît Ctrl/Cmd + A, C, X, et rien d'autre", () => {
    expect(shortcutOf({ key: "a", ctrlKey: true, metaKey: false })).toBe("select-all");
    expect(shortcutOf({ key: "C", ctrlKey: true, metaKey: false })).toBe("copy");
    expect(shortcutOf({ key: "x", ctrlKey: false, metaKey: true })).toBe("move");
    expect(shortcutOf({ key: "a", ctrlKey: false, metaKey: false })).toBeNull();
    expect(shortcutOf({ key: "v", ctrlKey: true, metaKey: false })).toBeNull();
    expect(shortcutOf({ key: "a", ctrlKey: true, metaKey: false, altKey: true })).toBeNull();
  });
  it("ne détourne pas la frappe dans un champ de saisie", () => {
    expect(isTypingTarget(document.createElement("input"))).toBe(true);
    expect(isTypingTarget(document.createElement("textarea"))).toBe(true);
    expect(isTypingTarget(document.createElement("button"))).toBe(false);
    expect(isTypingTarget(null)).toBe(false);
  });
});
