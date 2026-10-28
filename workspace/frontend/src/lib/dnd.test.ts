import { canDropOn, decodeDrag, draggedElements, encodeDrag, isInternalDrag, DRAG_TYPE } from "./dnd";
import { WorkspaceElement } from "./types";

const el = (p: Partial<WorkspaceElement>): WorkspaceElement => ({ _id: "x", name: "n", eType: "file", eParent: null, owner: "u", ...p });
const folders = [
  el({ _id: "a", eType: "folder", eParent: null }),
  el({ _id: "b", eType: "folder", eParent: "a" }),
  el({ _id: "c", eType: "folder", eParent: null }),
];

describe("draggedElements", () => {
  const [a, b, c] = [el({ _id: "a" }), el({ _id: "b" }), el({ _id: "c" })];
  it("emporte la sélection si l'élément saisi en fait partie", () => expect(draggedElements(b, [a, b])).toEqual([a, b]));
  it("sinon l'élément seul", () => expect(draggedElements(c, [a, b])).toEqual([c]));
});

describe("canDropOn", () => {
  it("un document peut aller dans un dossier ou à la racine", () => {
    const doc = el({ _id: "d", eParent: "c" });
    expect(canDropOn("a", [doc], folders)).toBe(true);
    expect(canDropOn(null, [doc], folders)).toBe(true);
  });
  it("pas dans le dossier où il se trouve déjà", () => expect(canDropOn("c", [el({ eParent: "c" })], folders)).toBe(false));
  it("un dossier ne va ni dans lui-même ni dans un de ses sous-dossiers", () => {
    expect(canDropOn("a", [folders[0]], folders)).toBe(false);
    expect(canDropOn("b", [folders[0]], folders)).toBe(false);
    expect(canDropOn("c", [folders[0]], folders)).toBe(true);
  });
  it("rien à déposer", () => expect(canDropOn("a", [], folders)).toBe(false));
});

describe("transport", () => {
  it("encode et décode les identifiants, ignore le contenu illisible", () => {
    expect(decodeDrag(encodeDrag([el({ _id: "a" }), el({ _id: "b" })]))).toEqual(["a", "b"]);
    expect(decodeDrag("pas du json")).toEqual([]);
    expect(decodeDrag(undefined)).toEqual([]);
    expect(decodeDrag('{"a":1}')).toEqual([]);
  });
  it("reconnaît un glisser interne", () => {
    expect(isInternalDrag([DRAG_TYPE, "text/plain"])).toBe(true);
    expect(isInternalDrag(["Files"])).toBe(false);
  });
});
