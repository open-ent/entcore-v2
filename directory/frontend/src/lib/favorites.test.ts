import { describe, expect, it } from "vitest";
import { addMember, favoriteBody, favoriteProblem, Member, membersOf, removeMember } from "./favorites";

const u1: Member = { id: "1", kind: "user", name: "Alice" };
const g1: Member = { id: "g", kind: "group", name: "Classe 6A" };

describe("favoris de partage", () => {
  it("exige un nom raisonnable et au moins un membre", () => {
    expect(favoriteProblem("", [u1])).toBe("name");
    expect(favoriteProblem("   ", [u1])).toBe("name");
    expect(favoriteProblem("a".repeat(61), [u1])).toBe("name");
    expect(favoriteProblem("Mes collègues", [])).toBe("members");
    expect(favoriteProblem("Mes collègues", [u1])).toBeNull();
  });

  it("ajoute sans doublon et retire un membre", () => {
    expect(addMember([u1], u1)).toEqual([u1]);
    expect(addMember([u1], g1)).toEqual([u1, g1]);
    expect(removeMember([u1, g1], "1")).toEqual([g1]);
  });

  it("construit le corps de la requête", () => {
    expect(favoriteBody("  Équipe ", [u1, g1])).toEqual({ name: "Équipe", members: ["1", "g"] });
  });

  it("lit les membres d'un favori, groupes d'abord", () => {
    expect(membersOf({ users: [{ id: "1", displayName: "Alice" }], groups: [{ id: "g", name: "6A" }] })).toEqual([
      { id: "g", kind: "group", name: "6A" },
      { id: "1", kind: "user", name: "Alice" },
    ]);
    expect(membersOf({})).toEqual([]);
  });
});
