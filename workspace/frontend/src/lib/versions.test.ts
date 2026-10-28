import { canDeleteComment, canDeleteRevision, newestFirst } from "./versions";
import { Revision } from "./types";

const none = { read: true, contrib: false, manager: false, comment: false };
const rev = (p: Partial<Revision>): Revision => ({ _id: "r", documentId: "d", name: "n", userId: "u", ...p });

describe("canDeleteRevision", () => {
  it("jamais la dernière version", () => {
    expect(canDeleteRevision(rev({}), { ...none, manager: true }, "me", 1)).toBe(false);
  });
  it("un gestionnaire supprime toute version", () => {
    expect(canDeleteRevision(rev({ userId: "autre" }), { ...none, manager: true }, "me", 3)).toBe(true);
  });
  it("un contributeur supprime les siennes seulement", () => {
    const c = { ...none, contrib: true };
    expect(canDeleteRevision(rev({ userId: "me" }), c, "me", 3)).toBe(true);
    expect(canDeleteRevision(rev({ userId: "autre" }), c, "me", 3)).toBe(false);
  });
  it("un lecteur ne supprime rien", () => expect(canDeleteRevision(rev({ userId: "me" }), none, "me", 3)).toBe(false));
});

describe("canDeleteComment", () => {
  const c = { id: "c", author: "me", comment: "x" };
  it("l'auteur supprime le sien s'il peut commenter", () => {
    expect(canDeleteComment(c, { ...none, comment: true }, "me")).toBe(true);
    expect(canDeleteComment(c, none, "me")).toBe(false);
  });
  it("un gestionnaire supprime tout", () => expect(canDeleteComment({ ...c, author: "autre" }, { ...none, manager: true }, "me")).toBe(true));
  it("un autre commentateur ne supprime pas celui d'autrui", () => expect(canDeleteComment({ ...c, author: "autre" }, { ...none, comment: true }, "me")).toBe(false));
});

describe("newestFirst", () => {
  it("trie de la plus récente à la plus ancienne", () => {
    const list = [rev({ _id: "a", date: { $date: "2026-10-01T10:00:00Z" } }), rev({ _id: "b", date: { $date: "2026-10-03T10:00:00Z" } })];
    expect(newestFirst(list).map((r) => r._id)).toEqual(["b", "a"]);
  });
});
