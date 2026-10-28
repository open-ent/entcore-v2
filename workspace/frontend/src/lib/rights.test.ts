import { rightsOn } from "./rights";
import { WorkspaceElement } from "./types";

const P = "org-entcore-workspace-controllers-WorkspaceController|";
const el = (p: Partial<WorkspaceElement>): WorkspaceElement => ({ _id: "x", name: "n", eType: "folder", eParent: null, owner: "owner", ...p });

describe("rightsOn", () => {
  it("le propriétaire a tous les droits", () => {
    expect(rightsOn(el({}), "owner")).toEqual({ read: true, contrib: true, manager: true, comment: true });
  });
  it("un partage direct en contribution donne contribution et lecture, pas la gestion", () => {
    const e = el({ shared: [{ userId: "me", [P + "updateDocument"]: true }] });
    expect(rightsOn(e, "me")).toEqual({ read: true, contrib: true, manager: false, comment: false });
  });
  it("un partage à un groupe de l'usager compte, celui d'un autre groupe non", () => {
    const e = el({ shared: [{ groupId: "g1", [P + "getDocument"]: true }] });
    expect(rightsOn(e, "me", ["g1"]).read).toBe(true);
    expect(rightsOn(e, "me", ["g2"]).read).toBe(false);
  });
  it("les partages hérités d'un dossier parent comptent", () => {
    const e = el({ inheritedShares: [{ userId: "me", [P + "shareJson"]: true }] });
    expect(rightsOn(e, "me")).toEqual({ read: true, contrib: true, manager: true, comment: true });
  });
  it("le droit de commenter est distinct de la contribution", () => {
    const e = el({ shared: [{ userId: "me", [P + "commentDocument"]: true }] });
    expect(rightsOn(e, "me").comment).toBe(true);
    expect(rightsOn(e, "me").contrib).toBe(false);
  });
  it("sans partage, aucun droit", () => {
    expect(rightsOn(el({}), "me")).toEqual({ read: false, contrib: false, manager: false, comment: false });
    expect(rightsOn(el({}), undefined)).toEqual({ read: false, contrib: false, manager: false, comment: false });
  });
});
