import { describe, expect, it } from "vitest";
import { addUsersBody, discoverAllowed, discoverBody, leaveGroupBody } from "./discover";

describe("découvrir les visibles", () => {
  it("n'est proposé qu'aux profils acceptés", () => {
    expect(discoverAllowed("ENSEIGNANT", [{ type: "Teacher" }])).toBe(true);
    expect(discoverAllowed("PERSEDUCNAT", [{ type: "Teacher" }])).toBe(false);
    expect(discoverAllowed("PERSEDUCNAT", [{ type: "Personnel" }])).toBe(true);
    expect(discoverAllowed("ELEVE", [{ type: "Teacher" }, { type: "Personnel" }])).toBe(false);
    expect(discoverAllowed("Teacher", [])).toBe(false);
  });

  it("construit la recherche avec les seuls filtres renseignés", () => {
    expect(discoverBody(" Marie ", [], [])).toEqual({ search: "marie" });
    expect(discoverBody("m", ["s1"], ["Teacher"])).toEqual({ search: "m", structures: ["s1"], profiles: ["Teacher"] });
  });

  it("ajoute des membres sans doublon et permet de quitter un groupe", () => {
    expect(addUsersBody(["a"], ["a", "b"])).toEqual({ oldUsers: ["a"], newUsers: ["a", "b"] });
    expect(leaveGroupBody(["a", "me"], "me")).toEqual({ oldUsers: ["a", "me"], newUsers: ["a"] });
  });
});
