import { describe, expect, it } from "vitest";
import { canPickTheme, parseThemeConf, themesOfCurrent } from "./themes";

const CONF = `exports.conf = {
  overriding: [
    { parent: "theme-open-ent", child: "neoconnect", group: "neoconnect", skins: ["default"], help: "/help-2d" },
    { parent: "panda", child: "cd16", group: "cd16", skins: ["default"] },
    { parent: "theme-open-ent", child: "cd16b", group: "cd16", skins: ["default"] },
    { parent: "solo", child: "solo" },
  ],
};`;

describe("thèmes", () => {
  it("lit les entrées du fichier de configuration", () => {
    const e = parseThemeConf(CONF);
    expect(e.map((x) => x.child)).toEqual(["neoconnect", "cd16", "cd16b", "solo"]);
    expect(e[1]).toEqual({ parent: "panda", child: "cd16", group: "cd16" });
    expect(parseThemeConf("n'importe quoi")).toEqual([]);
  });

  it("ne propose que les thèmes du groupe du thème courant", () => {
    const e = parseThemeConf(CONF);
    expect(themesOfCurrent(e, "cd16").map((x) => x.child)).toEqual(["cd16", "cd16b"]);
    expect(themesOfCurrent(e, "inconnu")).toEqual([]);
    expect(canPickTheme(themesOfCurrent(e, "cd16"))).toBe(true);
    expect(canPickTheme(themesOfCurrent(e, "neoconnect"))).toBe(false);
  });
});
