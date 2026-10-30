import { describe, expect, it } from "vitest";
import { complexityLevel, complexityPercent, passwordComplexity, passwordProblem } from "./password";

describe("mot de passe", () => {
  it("note la robustesse comme l'ancienne interface", () => {
    expect(passwordComplexity("")).toBe(0);
    expect(passwordComplexity("abc")).toBe(3);
    expect(passwordComplexity("abcdef")).toBe(6);
    expect(passwordComplexity("abcdef12")).toBe(13);
    expect(passwordComplexity("Abcdef12!")).toBe(9 + 5 + 5 + 5);
  });

  it("classe faible, modérée, forte", () => {
    expect(complexityLevel("abc")).toBe("weak");
    expect(complexityLevel("abcdef12")).toBe("moderate");
    expect(complexityLevel("Abcdefgh12!?")).toBe("strong");
  });

  it("plafonne la jauge à 100 %", () => {
    expect(complexityPercent("Abcdefgh12!?abcdefghijkl")).toBe(100);
    expect(complexityPercent("")).toBe(0);
  });

  it("repère le premier défaut du formulaire", () => {
    const regex = "^.{6,}$";
    expect(passwordProblem("", "abcdef", "abcdef", regex)).toBe("empty-old");
    expect(passwordProblem("old", "abc", "abc", regex)).toBe("pattern");
    expect(passwordProblem("old", "abcdef", "abcdeg", regex)).toBe("different");
    expect(passwordProblem("old", "abcdef", "abcdef", regex)).toBeNull();
    expect(passwordProblem("old", "abcdef", "abcdef", "(")).toBeNull();
  });
});
