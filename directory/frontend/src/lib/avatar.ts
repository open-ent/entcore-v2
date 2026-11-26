import { SyntheticEvent } from "react";

/** Illustration du thème de la plateforme pour un usager sans photo. */
export const NO_AVATAR = "/assets/themes/theme-open-ent/img/illustrations/no-avatar.svg";

/** Usager sans photo : `/userbook/avatar/<id>` répond 404 (image par défaut absente du stockage) → illustration du thème. */
export const onAvatarError = (e: SyntheticEvent<HTMLImageElement>) => {
  const img = e.currentTarget;
  if (!img.src.endsWith(NO_AVATAR)) img.src = NO_AVATAR;
};
