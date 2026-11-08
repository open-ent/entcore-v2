import { render } from "@testing-library/react";
import { WORKSPACE_CSS, WorkspaceStyle } from "./WorkspaceStyle";

describe("WorkspaceStyle", () => {
  it("masque propriétaire, date et poids et place la colonne de gauche au-dessus, sous 768 px", () => {
    expect(WORKSPACE_CSS).toContain("@media (max-width: 767px)");
    for (const c of [".ws-col-owner", ".ws-col-date", ".ws-col-size", ".ws-aside", ".ws-main"]) expect(WORKSPACE_CSS).toContain(c);
  });
  it("porte la grille des cartes et les repères de sélection et de dépôt", () => {
    for (const c of [".ws-grid", ".ws-card", ".ws-selected", ".ws-drop-target", ".ws-section-title"]) expect(WORKSPACE_CSS).toContain(c);
  });
  it("ne contient aucun accent grave : il fermerait le littéral de gabarit et casserait le module", () => {
    expect(WORKSPACE_CSS).not.toContain("`");
  });
  it("n'écrit aucune couleur en dur : le thème les fournit", () => {
    expect(WORKSPACE_CSS).not.toMatch(/#[0-9a-f]{3,8}\b/i);
  });
  it("s'insère dans la page", () => {
    const { container } = render(<WorkspaceStyle />);
    expect(container.querySelector("style[data-responsive]")?.textContent).toBe(WORKSPACE_CSS);
  });
});
