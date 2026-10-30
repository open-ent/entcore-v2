import { describe, expect, it } from "vitest";
import { apiErrorKind, classNames, formatBirthDate, isStudent, isValidPhone, loginError, profileRows } from "./profile";

describe("profil", () => {
  it("affiche la date de naissance en jour/mois/année", () => {
    expect(formatBirthDate("1971-02-28")).toBe("28/02/1971");
    expect(formatBirthDate(undefined)).toBe("");
    expect(formatBirthDate("inconnue")).toBe("inconnue");
  });

  it("ne garde que les lignes renseignées, l'alias d'identifiant primant sur l'identifiant", () => {
    const rows = profileRows({ id: "1", login: "a.b", loginAlias: "alias", lastName: "Dupont", email: "x@y.fr" }, undefined);
    expect(rows.map((r) => r.key)).toEqual(["lastName", "login", "email"]);
    expect(rows.find((r) => r.key === "login")?.value).toBe("alias");
  });

  it("complète depuis la fiche userbook quand l'annuaire ne donne rien", () => {
    const rows = profileRows(undefined, { id: "1", userId: "1", login: "l", displayName: "D", type: ["Personnel"], address: "1 rue", birthdate: "2000-01-02" });
    expect(rows.map((r) => `${r.key}=${r.value}`)).toEqual(["displayName=D", "login=l", "birthDate=02/01/2000", "address=1 rue"]);
  });

  it("liste les noms de classes quelle que soit leur forme, sans entrées vides", () => {
    expect(classNames([{ name: "6A" }, "5B", { name: "" }, {}])).toEqual(["6A", "5B"]);
    expect(classNames(undefined)).toEqual([]);
  });

  it("valide les numéros de téléphone comme l'ancienne interface", () => {
    expect(isValidPhone("")).toBe(true);
    expect(isValidPhone("+33 6 12 34 56 78")).toBe(true);
    expect(isValidPhone("0612345678")).toBe(true);
    expect(isValidPhone("12")).toBe(false);
    expect(isValidPhone("abc")).toBe(false);
  });

  it("valide l'identifiant : minuscules, chiffres, points", () => {
    expect(loginError("jean.dupont2")).toBeNull();
    expect(loginError("")).toBe("empty");
    expect(loginError("Jean")).toBe("pattern");
    expect(loginError("a b")).toBe("pattern");
    expect(loginError("a".repeat(65))).toBe("length");
  });

  it("reconnaît le profil élève et l'erreur d'identifiant déjà pris", () => {
    expect(isStudent(["Student"])).toBe(true);
    expect(isStudent(["Teacher"])).toBe(false);
    expect(isStudent(undefined)).toBe(false);
    expect(apiErrorKind("login already exists")).toBe("exists");
    expect(apiErrorKind("Cet identifiant existe déjà")).toBe("exists");
    expect(apiErrorKind("autre")).toBe("other");
  });
});
