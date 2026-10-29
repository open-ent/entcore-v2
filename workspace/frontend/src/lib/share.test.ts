import { sharePairs, shareForm } from "./share";

describe("partage groupé", () => {
  it("liste les personnes et groupes ayant des droits", () => {
    const pairs = sharePairs({ users: { checked: { u1: ["a", "b"], u2: [] } }, groups: { checked: { g1: ["c"] } } });
    expect(pairs).toEqual([
      { kind: "user", id: "u1", actions: ["a", "b"] },
      { kind: "group", id: "g1", actions: ["c"] },
    ]);
  });
  it("ignore un partage vide ou absent", () => {
    expect(sharePairs(undefined)).toEqual([]);
    expect(sharePairs({})).toEqual([]);
  });
  it("construit le formulaire attendu par le serveur", () => {
    expect(shareForm({ kind: "user", id: "u1", actions: ["a", "b"] }).toString()).toBe("userId=u1&actions=a&actions=b");
    expect(shareForm({ kind: "group", id: "g1", actions: ["c"] }).toString()).toBe("groupId=g1&actions=c");
  });
});
