import { folderNameError, isZip, planUpload, uploadUrl } from "./upload";

const f = (size: number) => ({ size, name: `f${size}` });

describe("folderNameError", () => {
  it("refuse un nom vide ou fait d'espaces", () => {
    expect(folderNameError("")).toBe("empty");
    expect(folderNameError("   ")).toBe("empty");
  });
  it("refuse le caractère _", () => expect(folderNameError("mon_dossier")).toBe("underscore"));
  it("accepte un nom ordinaire", () => expect(folderNameError(" Cours 6e ")).toBeNull());
});

describe("isZip", () => {
  it("reconnaît l'extension et le type MIME", () => {
    expect(isZip({ name: "a.ZIP" })).toBe(true);
    expect(isZip({ name: "a", type: "application/zip" })).toBe(true);
    expect(isZip({ name: "a.txt", type: "text/plain" })).toBe(false);
  });
});

describe("planUpload", () => {
  const MB = 1024 * 1024;
  it("accepte dans l'ordre tant que l'espace restant suffit", () => {
    const plan = planUpload([f(4 * MB), f(8 * MB), f(5 * MB)], { quota: 20 * MB, storage: 10 * MB }, 50 * MB);
    expect(plan.accepted.map((x) => x.size)).toEqual([4 * MB, 5 * MB]);
    expect(plan.rejected.map((r) => [r.file.size, r.reason])).toEqual([[8 * MB, "quota"]]);
    expect(plan.remaining).toBe(10 * MB);
  });
  it("refuse un fichier plus gros que la limite unitaire", () => {
    const plan = planUpload([f(60 * MB)], { quota: 1000 * MB, storage: 0 }, 50 * MB);
    expect(plan.rejected[0].reason).toBe("too-large");
  });
  it("sans quota connu, n'applique que la limite unitaire", () => {
    expect(planUpload([f(10)], undefined, 50).accepted).toHaveLength(1);
  });
});

describe("uploadUrl", () => {
  it("compose l'URL du document ou du zip", () => {
    expect(uploadUrl({})).toBe("/workspace/document");
    expect(uploadUrl({ parentId: "abc", image: true })).toBe("/workspace/document?quality=1&parentId=abc");
    expect(uploadUrl({ zip: true, parentId: "abc", image: true })).toBe("/workspace/zip?parentId=abc");
  });
});
