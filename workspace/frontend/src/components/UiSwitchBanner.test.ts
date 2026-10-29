import { bannerVisible, MAX_DISPLAYS } from "./UiSwitchBanner";

describe("bannerVisible", () => {
  it("s'affiche tant qu'aucun choix n'est posé", () => {
    expect(bannerVisible({}, false)).toBe(true);
  });
  it("disparaît dès qu'un choix est posé dans les paramètres, qu'il soit React ou AngularJS", () => {
    expect(bannerVisible({ ui: "react" }, false)).toBe(false);
    expect(bannerVisible({ ui: "angular" }, false)).toBe(false);
  });
  it("disparaît après « Ne plus afficher », fermeture, ou le nombre maximal d'affichages", () => {
    expect(bannerVisible({ returnDismissed: true }, false)).toBe(false);
    expect(bannerVisible({}, true)).toBe(false);
    expect(bannerVisible({ returnShown: MAX_DISPLAYS }, false)).toBe(false);
    expect(bannerVisible({ returnShown: MAX_DISPLAYS - 1 }, false)).toBe(true);
  });
  it("attend la préférence avant de s'afficher", () => {
    expect(bannerVisible(undefined, false)).toBe(false);
  });
});
