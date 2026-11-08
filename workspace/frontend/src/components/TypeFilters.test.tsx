import { fireEvent, render } from "@testing-library/react";
import { TypeFilters } from "./TypeFilters";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const counts = [
  { key: "all", count: 6 },
  { key: "folders", count: 3 },
  { key: "videos", count: 2 },
  { key: "documents", count: 1 },
] as const;

describe("TypeFilters", () => {
  it("ne propose rien quand il n'y a qu'une famille à filtrer", () => {
    const { container } = render(
      <TypeFilters counts={[{ key: "all", count: 3 }, { key: "folders", count: 3 }]} value="all" onChange={() => undefined} />,
    );
    expect(container.querySelector("[data-type-filters]")).toBeNull();
  });

  it("affiche une puce par famille, avec son compte", () => {
    const { container } = render(<TypeFilters counts={[...counts]} value="all" onChange={() => undefined} />);
    const chips = [...container.querySelectorAll("[data-type-filter]")];
    expect(chips.map((c) => c.getAttribute("data-type-filter"))).toEqual(["all", "folders", "videos", "documents"]);
    expect(chips[2].textContent).toContain("2");
  });

  it("marque la famille choisie et signale le changement", () => {
    const chosen: string[] = [];
    const { container } = render(<TypeFilters counts={[...counts]} value="videos" onChange={(k) => chosen.push(k)} />);
    expect(container.querySelector('[data-type-filter="videos"]')?.getAttribute("aria-pressed")).toBe("true");
    expect(container.querySelector('[data-type-filter="all"]')?.getAttribute("aria-pressed")).toBe("false");
    fireEvent.click(container.querySelector('[data-type-filter="documents"]') as Element);
    expect(chosen).toEqual(["documents"]);
  });
});
