import { render, screen } from "@testing-library/react";
import { Home } from "./Home";

vi.mock("@open-ent/react", () => ({ AppHeader: ({ children }: { children: unknown }) => <div>{children as never}</div> }));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

describe("Home", () => {
  it("affiche le titre de l'espace documentaire", () => {
    render(<Home />);
    expect(screen.getByText("workspace.title")).toBeInTheDocument();
  });
});
