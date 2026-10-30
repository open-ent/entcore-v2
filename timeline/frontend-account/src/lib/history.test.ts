import { describe, expect, it } from "vitest";
import {
  accessibleTypes,
  mergePage,
  notificationsQuery,
  sanitizeMessage,
  toggleAll,
  toggleType,
} from "./history";

describe("historique des notifications", () => {
  it("ne propose que les types dont l'utilisateur a l'application", () => {
    const registered = [
      { type: "BLOG" },
      { type: "XNOTIF", "app-name": "Messagerie" },
      { type: "WIKI" },
    ];
    expect(
      accessibleTypes(["BLOG", "XNOTIF", "WIKI", "PAD"], registered, [
        "blog",
        "Messagerie",
      ]),
    ).toEqual(["BLOG", "XNOTIF"]);
  });

  it("construit la requête avec un type par paramètre", () => {
    expect(notificationsQuery(["BLOG", "WIKI"], 2)).toBe(
      "page=2&type=BLOG&type=WIKI&mine=1",
    );
  });

  it("coche, décoche et inverse les filtres", () => {
    expect(toggleType(["A"], "B")).toEqual(["A", "B"]);
    expect(toggleType(["A", "B"], "A")).toEqual(["B"]);
    expect(toggleAll(["A", "B"], ["A", "B"])).toEqual([]);
    expect(toggleAll(["A"], ["A", "B"])).toEqual(["A", "B"]);
  });

  it("fusionne une page sans doublon", () => {
    const n = (id: string) => ({
      _id: id,
      type: "T",
      message: "m",
      date: { $date: 1 },
    });
    expect(
      mergePage([n("1"), n("2")], [n("2"), n("3")]).map((x) => x._id),
    ).toEqual(["1", "2", "3"]);
  });

  it("nettoie le message : pas de script, pas de lien javascript:, pas d'attribut d'événement", () => {
    const out = sanitizeMessage(
      '<b onclick="x()">Alice</b> a publié <a href="javascript:alert(1)" onclick="y()">un billet</a><script>alert(1)</script> <a href="/blog/1">ici</a>',
    );
    expect(out).not.toMatch(/script|onclick|javascript/i);
    expect(out).toContain("<b>Alice</b>");
    expect(out).toContain('href="/blog/1"');
  });
});
