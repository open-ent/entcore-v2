import { describe, expect, it } from "vitest";
import { groupSearchBody, searchBody, sortGroups, sortVisibles, toggleValue } from "./directory";

describe("annuaire — recherche", () => {
  it("n'envoie que les filtres renseignés, texte en minuscules", () => {
    expect(searchBody("  Dupont ", { profiles: ["Teacher"], classes: [], structures: undefined })).toEqual({
      search: "dupont",
      types: ["User"],
      mood: true,
      profiles: ["Teacher"],
    });
  });

  it("trie par nom sans tenir compte des accents ni de la casse", () => {
    const sorted = sortVisibles([
      { id: "1", displayName: "Zoé" },
      { id: "2", displayName: "émile" },
      { id: "3", displayName: "Alice" },
    ]);
    expect(sorted.map((v) => v.id)).toEqual(["3", "2", "1"]);
  });

  it("coche et décoche une valeur de filtre", () => {
    expect(toggleValue(undefined, "a")).toEqual(["a"]);
    expect(toggleValue(["a", "b"], "a")).toEqual(["b"]);
  });

  it("recherche de groupes : types Group, sans filtre de profil", () => {
    expect(groupSearchBody(" Classe ", { profiles: ["Teacher"], structures: ["s1"] })).toEqual({ search: "classe", types: ["Group"], structures: ["s1"] });
  });

  it("trie les groupes sur le nom de tri puis le nom", () => {
    const sorted = sortGroups([
      { id: "1", name: "B", sortName: "A-Teacher" },
      { id: "2", name: "A" },
      { id: "3", name: "C", sortName: "A-Student" },
    ]);
    expect(sorted.map((g) => g.id)).toEqual(["2", "3", "1"]);
  });
});
