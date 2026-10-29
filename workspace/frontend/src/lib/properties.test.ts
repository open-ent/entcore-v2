import { cleanName, isForbiddenChar, managersOf, MAX_NAME, ParentInfo } from "./properties";

const PREFIX = "org-entcore-workspace-controllers-WorkspaceController|";
const parent = (p: Partial<ParentInfo>): ParentInfo => ({ _id: "p", name: "Parent", eType: "folder", eParent: null, owner: "u1", ownerName: "Alice", ...p });

describe("propriétés — nom", () => {
  it("refuse / \\ < > | à la frappe, accepte le reste", () => {
    for (const c of ["/", "\\", "<", ">", "|"]) expect(isForbiddenChar(c)).toBe(true);
    for (const c of ["a", "é", " ", "-", "_", "Enter", "Backspace"]) expect(isForbiddenChar(c)).toBe(false);
  });
  it("retire les espaces de bord et les caractères interdits à l'enregistrement", () => {
    expect(cleanName("  a/b<c>|d\\e  ")).toBe("abcde");
    expect(cleanName("   ")).toBe("");
  });
  it("limite la longueur d'un nom à 80 caractères", () => expect(MAX_NAME).toBe(80));
});

describe("propriétés — gestionnaires du dossier parent", () => {
  it("le propriétaire d'abord, puis chaque personne ou groupe pouvant renommer", () => {
    const m = managersOf(
      parent({
        inheritedShares: [
          { userId: "u2", [PREFIX + "renameFolder"]: true },
          { groupId: "g1", [PREFIX + "renameDocument"]: true },
          { userId: "u3", [PREFIX + "getDocument"]: true },
        ],
        visibleUsers: [{ id: "u2", username: "Bob" }],
        visibleGroups: [{ id: "g1", name: "Équipe" }],
      }),
    );
    expect(m).toEqual([
      { id: "u1", name: "Alice", uri: "/userbook/annuaire#/u1" },
      { id: "u2", name: "Bob", uri: "/userbook/annuaire#/u2" },
      { id: "g1", name: "Équipe", uri: "/userbook/annuaire#/group-view/g1" },
    ]);
  });
  it("le propriétaire n'apparaît qu'une fois", () => {
    expect(managersOf(parent({ inheritedShares: [{ userId: "u1", [PREFIX + "renameFolder"]: true }] }))).toHaveLength(1);
  });
  it("sans parent : aucun gestionnaire", () => expect(managersOf(undefined)).toEqual([]));
});
