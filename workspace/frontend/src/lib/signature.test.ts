import { availableActions } from "./actions";
import { SignatureReport, verdictOf } from "./signature";
import { WorkspaceElement } from "./types";

const report = (p: Partial<SignatureReport>): SignatureReport => ({ valide: false, intact: false, nbSignatures: 1, nbValides: 0, signatures: [], ...p });
const file = (name: string): WorkspaceElement => ({ _id: name, name, eType: "file", eParent: null, owner: "u", metadata: { filename: name } as WorkspaceElement["metadata"] });

describe("verdictOf", () => {
  it("distingue les quatre réponses d'esign", () => {
    expect(verdictOf(report({ nbSignatures: 0 }))).toBe("none");
    expect(verdictOf(report({ valide: true, intact: true, nbValides: 1 }))).toBe("valid");
    expect(verdictOf(report({ intact: true }))).toBe("unknown");
    expect(verdictOf(report({}))).toBe("invalid");
  });
});

describe("action « Vérifier la signature »", () => {
  it("un seul PDF, dans tous les arbres lisibles, jamais dans la corbeille", () => {
    expect(availableActions("owner", [file("a.pdf")])).toContain("verifySignature");
    expect(availableActions("shared", [file("a.pdf")])).toContain("verifySignature");
    expect(availableActions("public", [file("a.pdf")])).toContain("verifySignature");
    expect(availableActions("owner", [file("a.pdf"), file("b.pdf")])).not.toContain("verifySignature");
    expect(availableActions("owner", [file("a.docx")])).not.toContain("verifySignature");
    expect(availableActions("trash", [file("a.pdf")])).not.toContain("verifySignature");
  });
});
