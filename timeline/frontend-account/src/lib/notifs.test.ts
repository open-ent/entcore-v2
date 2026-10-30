import { describe, expect, it } from "vitest";
import {
  AppGroup,
  commonFrequency,
  Default,
  groupByApp,
  preferenceBody,
  setActionFrequency,
  setGroupFrequency,
  withPreferences,
} from "./notifs";

const d = (
  key: string,
  type: string,
  extra: Partial<Default> = {},
): Default => ({ key, type, defaultFrequency: "IMMEDIATE", ...extra });

describe("notifications externes", () => {
  it("regroupe par application et écarte INTERNAL, HIDDEN et les applications absentes", () => {
    const groups = groupByApp(
      [
        d("blog.a", "BLOG"),
        d("blog.b", "BLOG"),
        d("wiki.a", "WIKI", { restriction: "INTERNAL" }),
        d("forum.a", "FORUM", { restriction: "HIDDEN" }),
        d("pad.a", "PAD"),
        d("x.a", "XNOTIF", { "app-name": "Messagerie" }),
      ],
      ["Blog", "Messagerie", "Wiki", "Forum"],
    );
    expect(groups.map((g) => g.type)).toEqual(["BLOG", "XNOTIF"]);
    expect(groups[0].actions).toHaveLength(2);
  });

  it("applique les préférences enregistrées", () => {
    const groups = withPreferences(
      groupByApp([d("blog.a", "BLOG")], ["blog"]),
      { config: { "blog.a": { defaultFrequency: "DAILY" } } },
    );
    expect(groups[0].actions[0].defaultFrequency).toBe("DAILY");
    expect(withPreferences(groups, null)[0].actions[0].defaultFrequency).toBe(
      "DAILY",
    );
  });

  it("n'a de fréquence commune que si toutes les notifications coïncident", () => {
    let g: AppGroup[] = [
      { type: "BLOG", actions: [d("a", "BLOG"), d("b", "BLOG")] },
    ];
    expect(commonFrequency(g[0])).toBe("IMMEDIATE");
    g = setActionFrequency(g, "b", "NEVER");
    expect(commonFrequency(g[0])).toBeUndefined();
    g = setGroupFrequency(g, "BLOG", "WEEKLY");
    expect(commonFrequency(g[0])).toBe("WEEKLY");
    expect(g[0].actions.map((a) => a.defaultFrequency)).toEqual([
      "WEEKLY",
      "WEEKLY",
    ]);
  });

  it("conserve les autres préférences à l'enregistrement", () => {
    const groups: AppGroup[] = [
      {
        type: "BLOG",
        actions: [d("a", "BLOG", { defaultFrequency: "NEVER" })],
      },
    ];
    const body = preferenceBody(
      { config: { z: { defaultFrequency: "DAILY" } }, other: 1 } as never,
      groups,
    );
    expect(body).toEqual({
      other: 1,
      config: {
        z: { defaultFrequency: "DAILY" },
        a: { defaultFrequency: "NEVER" },
      },
    });
  });
});
