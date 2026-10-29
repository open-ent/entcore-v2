import { destinations, directionOf, errorKeyOf, impactKeys, isSharedElement, transferKeys } from "./transfer";
import { WorkspaceElement } from "./types";

const el = (p: Partial<WorkspaceElement>): WorkspaceElement => ({ _id: "x", name: "n", eType: "folder", eParent: null, owner: "u1", ...p });
const PREFIX = "org-entcore-workspace-controllers-WorkspaceController|";
const sharedWith = (rights: string[]) => [{ userId: "u1", ...Object.fromEntries(rights.map((r) => [PREFIX + r, true])) }];

describe("transfert — sens", () => {
  const perso = el({});
  const partage = el({ shared: [{ userId: "u2" }] });
  it("reconnaît un élément partagé (droits posés ou hérités)", () => {
    expect(isSharedElement(perso)).toBe(false);
    expect(isSharedElement(partage)).toBe(true);
    expect(isSharedElement(el({ inheritedShares: [{ groupId: "g" }] }))).toBe(true);
  });
  it("perso vers partagé, partagé vers perso, partagé vers partagé, perso vers perso", () => {
    expect(directionOf([perso], true)).toBe("toshare");
    expect(directionOf([partage], false)).toBe("toown");
    expect(directionOf([partage], true)).toBe("fromtoshare");
    expect(directionOf([perso], false)).toBe("nope");
  });
  it("une sélection mêlée vers un partage : « toshare » dès qu'un élément est personnel", () => {
    expect(directionOf([perso, partage], true)).toBe("toshare");
    expect(directionOf([perso, partage], false)).toBe("toown");
  });
});

describe("transfert — destinations", () => {
  const mine = el({ _id: "a", owner: "u1" });
  const mineShared = el({ _id: "b", owner: "u1", shared: sharedWith(["shareJson"]) });
  const contrib = el({ _id: "c", owner: "u2", shared: sharedWith(["updateDocument"]) });
  const readOnly = el({ _id: "d", owner: "u2", shared: sharedWith(["getDocument"]) });
  const trashed = el({ _id: "e", owner: "u1", trasher: "u1" });
  it("dossiers personnels d'un côté, partagés avec droit de contribution de l'autre", () => {
    const d = destinations([mine, mineShared, contrib, readOnly, trashed], "u1", []);
    expect(d.personal.map((f) => f._id)).toEqual(["a"]);
    expect(d.shared.map((f) => f._id)).toEqual(["b", "c"]);
  });
});

describe("transfert — messages", () => {
  it("clés propres à la copie et au déplacement", () => {
    expect(transferKeys("copy").processing).toBe("workspace.copy.window.processing");
    expect(transferKeys("move").finished).toBe("workspace.move.window.finished");
  });
  it("avertissement selon le sens ; autres éléments que les siens : on en devient gestionnaire", () => {
    expect(impactKeys("toshare", true).title).toBe("workspace.move.warnings.title.toshare");
    expect(impactKeys("fromtoshare", false).messages).toContain("workspace.move.window.fromtoshare");
    expect(impactKeys("toown", true).messages).toEqual(["workspace.move.window.toown", "workspace.move.window.copyinsteadofmove"]);
    expect(impactKeys("toown", false).messages).toEqual(["workspace.move.window.fromshare.notmine"]);
  });
  it("lit la clé d'erreur du serveur, sinon le message par défaut", () => {
    expect(errorKeyOf(new Error("workspace.contrib.cant"))).toBe("workspace.contrib.cant");
    expect(errorKeyOf(new Error("500"))).toBe("workspace.react.action.failed");
    expect(errorKeyOf(undefined)).toBe("workspace.react.action.failed");
    expect(errorKeyOf(new Error("pas une clé"))).toBe("workspace.react.action.failed");
  });
});
