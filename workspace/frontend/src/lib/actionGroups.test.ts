import { groupActions, BarLayout } from "./actionGroups";

type Id = "a" | "b" | "c" | "d" | "e" | "x";
const layout: BarLayout<Id> = { direct: ["a"], menus: [{ key: "m1", label: "m1", ids: ["b", "c"] }, { key: "m2", label: "m2", ids: ["d", "e"] }], danger: ["x"] };

describe("groupActions", () => {
  it("garde une action directe en bouton et range les autres dans leur menu", () => {
    expect(groupActions<Id>(["a", "b", "c", "d", "e", "x"], layout)).toEqual({
      entries: [{ kind: "button", id: "a" }, { kind: "menu", key: "m1", label: "m1", ids: ["b", "c"] }, { kind: "menu", key: "m2", label: "m2", ids: ["d", "e"] }],
      danger: ["x"],
    });
  });
  it("un menu réduit à une seule action redevient un bouton", () => {
    expect(groupActions<Id>(["b", "d", "e"], layout).entries).toEqual([{ kind: "button", id: "b" }, { kind: "menu", key: "m2", label: "m2", ids: ["d", "e"] }]);
  });
  it("n'affiche pas un menu sans action possible, ni d'action indisponible", () => {
    expect(groupActions<Id>(["a"], layout)).toEqual({ entries: [{ kind: "button", id: "a" }], danger: [] });
    expect(groupActions<Id>([], layout)).toEqual({ entries: [], danger: [] });
  });
});
