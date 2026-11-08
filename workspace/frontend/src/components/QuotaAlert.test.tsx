import { render } from "@testing-library/react";
import { QuotaAlert, quotaRatio } from "./QuotaAlert";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const show = (storage: number, quota = 100) =>
  render(<QuotaAlert quota={{ storage, quota }} onShowHeaviest={() => undefined} onEmptyTrash={() => undefined} />);

describe("quotaRatio", () => {
  it("vaut 0 sans quota connu ou avec un quota nul", () => {
    expect(quotaRatio(undefined)).toBe(0);
    expect(quotaRatio({ storage: 10, quota: 0 })).toBe(0);
  });
});

describe("QuotaAlert", () => {
  it("ne prévient pas sous 90 %", () => {
    const { container } = show(50);
    expect(container.querySelector("[data-workspace-quota-alert]")).toBeNull();
  });
  it("prévient entre 90 et 100 %", () => {
    const { container } = show(95);
    expect(container.querySelector('[data-workspace-quota-alert="near"]')).not.toBeNull();
  });
  it("alerte quand l'espace est dépassé, et propose les deux remèdes", () => {
    const { container } = show(154);
    expect(container.querySelector('[data-workspace-quota-alert="full"]')).not.toBeNull();
    expect(container.querySelector("[data-quota-heaviest]")).not.toBeNull();
    expect(container.querySelector("[data-quota-empty-trash]")).not.toBeNull();
  });
});
