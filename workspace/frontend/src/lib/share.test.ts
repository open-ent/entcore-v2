import { shareResources } from "./share";
import { WorkspaceElement } from "./types";

const el = (_id: string, owner: string, shared?: WorkspaceElement["shared"]): WorkspaceElement => ({
  _id,
  name: _id,
  eType: "file",
  eParent: null,
  owner,
  shared,
});

describe("partage multi-ressources", () => {
  it("une entrée par élément, dans l'ordre de la sélection", () => {
    expect(shareResources([el("a", "u1"), el("b", "u1")], "u1").map((r) => r.resourceId)).toEqual(["a", "b"]);
  });
  it("le propriétaire n'a pas de droits à réinjecter", () => {
    expect(shareResources([el("a", "u1")], "u1")).toEqual([{ resourceId: "a", resourceRights: [] }]);
  });
  it("reprend les droits directs de l'usager, pas ceux des autres ni des groupes", () => {
    const shared = [
      { userId: "u2", "X|shareJson": true },
      { groupId: "g1", "X|getDocument": true },
      { userId: "u2", "X|getDocument": true, "X|updateDocument": false },
    ];
    const [r] = shareResources([el("a", "u1", shared)], "u2");
    expect(r.resourceRights).toEqual(["user:u2:X|shareJson", "user:u2:X|getDocument"]);
  });
  it("sans usager connu, aucune liste de droits", () => {
    expect(shareResources([el("a", "u1", [{ userId: "u2", "X|getDocument": true }])], undefined)[0].resourceRights).toEqual([]);
  });
});
