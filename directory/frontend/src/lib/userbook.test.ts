import { describe, expect, it } from "vitest";
import { hobbyIsPublic, isVisible, mottoChanged, nextVisibility, showsChildren, showsRelatives, toggledHobbyVisibility } from "./userbook";

describe("userbook", () => {
  it("lit la visibilité des informations dans la fiche", () => {
    const person = { id: "1", userId: "1", login: "l", displayName: "d", type: [], visibleInfos: ["SHOW_EMAIL", "SHOW_PHONE"] };
    expect(isVisible(person, "email")).toBe(true);
    expect(isVisible(person, "phone")).toBe(true);
    expect(isVisible(person, "mobile")).toBe(false);
    expect(isVisible(undefined, "email")).toBe(false);
  });

  it("bascule la visibilité", () => {
    expect(nextVisibility(true)).toBe("prive");
    expect(nextVisibility(false)).toBe("public");
    expect(hobbyIsPublic("PUBLIC")).toBe(true);
    expect(hobbyIsPublic("PRIVE")).toBe(false);
    expect(hobbyIsPublic("")).toBe(false);
    expect(toggledHobbyVisibility("PUBLIC")).toBe("PRIVE");
    expect(toggledHobbyVisibility("")).toBe("PUBLIC");
  });

  it("détecte une devise modifiée", () => {
    expect(mottoChanged("a", "a")).toBe(false);
    expect(mottoChanged("a", "b")).toBe(true);
  });

  it("montre enfants ou responsables selon le profil", () => {
    expect(showsChildren("ENSEIGNANT")).toBe(true);
    expect(showsChildren("ELEVE")).toBe(false);
    expect(showsRelatives("ELEVE")).toBe(true);
    expect(showsRelatives("ENSEIGNANT")).toBe(false);
  });
});
