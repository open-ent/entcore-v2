import { render } from "@testing-library/react";
import { RESPONSIVE_CSS, ResponsiveStyle } from "./ResponsiveStyle";

describe("ResponsiveStyle", () => {
  it("masque propriétaire, date et poids et place la colonne de gauche au-dessus, sous 768 px", () => {
    expect(RESPONSIVE_CSS).toContain("@media (max-width: 767px)");
    for (const c of [".ws-col-owner", ".ws-col-date", ".ws-col-size", ".ws-aside", ".ws-main"]) expect(RESPONSIVE_CSS).toContain(c);
  });
  it("s'insère dans la page", () => {
    const { container } = render(<ResponsiveStyle />);
    expect(container.querySelector("style[data-responsive]")?.textContent).toBe(RESPONSIVE_CSS);
  });
});
