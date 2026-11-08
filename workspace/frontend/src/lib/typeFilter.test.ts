import { applyTypeFilter, filterKeyOf, typeFilterCounts } from "./typeFilter";
import { WorkspaceElement } from "./types";

const file = (name: string, type: string): WorkspaceElement =>
  ({ _id: name, name, eType: "file", eParent: null, owner: "u1", metadata: { "content-type": type } }) as WorkspaceElement;
const folder = (name: string): WorkspaceElement => ({ _id: name, name, eType: "folder", eParent: null, owner: "u1" }) as WorkspaceElement;

const sample = [folder("a"), folder("b"), folder("c"), file("v1", "video/mp4"), file("v2", "video/mp4"), file("notes.txt", "text/plain")];

describe("filterKeyOf", () => {
  it("range un dossier à part et regroupe texte, pdf et traitement de texte en « documents »", () => {
    expect(filterKeyOf(folder("a"))).toBe("folders");
    expect(filterKeyOf(file("notes.txt", "text/plain"))).toBe("documents");
    expect(filterKeyOf(file("cours.pdf", "application/pdf"))).toBe("documents");
    expect(filterKeyOf(file("devoir.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))).toBe("documents");
    expect(filterKeyOf(file("notes.ods", "application/vnd.oasis.opendocument.spreadsheet"))).toBe("sheets");
  });
});

describe("typeFilterCounts", () => {
  it("compte « Tout » puis seulement les familles présentes, dans l'ordre", () => {
    expect(typeFilterCounts(sample)).toEqual([
      { key: "all", count: 6 },
      { key: "folders", count: 3 },
      { key: "videos", count: 2 },
      { key: "documents", count: 1 },
    ]);
  });
  it("ne propose rien sur une liste vide", () => {
    expect(typeFilterCounts([])).toEqual([]);
  });
});

describe("applyTypeFilter", () => {
  it("ne retire rien avec « Tout »", () => {
    expect(applyTypeFilter(sample, "all")).toHaveLength(6);
  });
  it("ne garde que la famille choisie", () => {
    expect(applyTypeFilter(sample, "videos").map((e) => e.name)).toEqual(["v1", "v2"]);
    expect(applyTypeFilter(sample, "documents").map((e) => e.name)).toEqual(["notes.txt"]);
  });
  it("rend une liste vide pour une famille absente", () => {
    expect(applyTypeFilter(sample, "archives")).toEqual([]);
  });
});
