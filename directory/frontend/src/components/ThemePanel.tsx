import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { canPickTheme, parseThemeConf, themesOfCurrent } from "../lib/themes";
import { accountService } from "../services/account.service";

/** Choix du thème d'affichage (ex. école / collège) : une case par thème du groupe du thème courant. */
export const ThemePanel = () => {
  const { t } = useTranslation("directory");
  const qc = useQueryClient();
  const data = useQuery({
    queryKey: ["themes"],
    queryFn: async () => {
      const [conf, current, preference] = await Promise.all([accountService.themeConf(), accountService.currentTheme(), accountService.themePreference()]);
      return { themes: themesOfCurrent(parseThemeConf(conf), current), selected: preference ?? current };
    },
  });
  if (data.isLoading) return null;
  const themes = data.data?.themes ?? [];
  if (!canPickTheme(themes)) {
    return (
      <p data-themes-none>{t("account.themes.none", { defaultValue: "Aucun autre thème n'est proposé pour votre plateforme." })}</p>
    );
  }
  return (
    <section data-account-themes>
      <h2 className="h4">{t("themes.title")}</h2>
      <div className="d-flex gap-24 flex-wrap">
        {themes.map((theme) => (
          <label key={theme.child} className="d-block text-center" data-theme-choice={theme.child}>
            <input
              type="radio"
              name="theme"
              checked={data.data?.selected === theme.child}
              onChange={async () => {
                await accountService.setThemePreference(theme.child);
                await qc.invalidateQueries({ queryKey: ["themes"] });
              }}
            />{" "}
            {t(`${theme.parent}.choice`)}
            <br />
            <img src={`/assets/themes/${theme.parent}/img/illustrations/${theme.parent}.png`} alt="" style={{ maxWidth: 220 }} />
          </label>
        ))}
      </div>
    </section>
  );
};
