import { Person, UserInfos } from "../services/account.service";

export interface ProfileRow {
  key: string;
  /** clé i18n du libellé */
  label: string;
  value: string;
}

/** Date `AAAA-MM-JJ` affichée `JJ/MM/AAAA` ; toute autre forme est rendue telle quelle. */
export const formatBirthDate = (value?: string | null): string => {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(value ?? "");
  return m ? `${m[3]}/${m[2]}/${m[1]}` : (value ?? "");
};

/** Les lignes d'identité affichées dans « Mon profil » : seules celles qui ont une valeur apparaissent. */
export const profileRows = (infos: UserInfos | undefined, person: Person | undefined): ProfileRow[] => {
  const rows: ProfileRow[] = [
    { key: "lastName", label: "userBook.profile.lastName", value: infos?.lastName ?? "" },
    { key: "firstName", label: "userBook.profile.firstName", value: infos?.firstName ?? "" },
    { key: "displayName", label: "userBook.profile.displayName", value: infos?.displayName ?? person?.displayName ?? "" },
    { key: "login", label: "userBook.profile.login", value: infos?.loginAlias || infos?.login || person?.login || "" },
    { key: "birthDate", label: "userBook.profile.birthdate", value: formatBirthDate(infos?.birthDate ?? person?.birthdate) },
    { key: "email", label: "userBook.profile.email", value: infos?.email ?? person?.email ?? "" },
    { key: "mobile", label: "userBook.profile.mobilePhone", value: infos?.mobile ?? person?.mobile ?? "" },
    { key: "tel", label: "userBook.profile.telephone", value: infos?.homePhone ?? person?.tel ?? "" },
    { key: "address", label: "userBook.profile.address", value: person?.address ?? "" },
  ];
  return rows.filter((r) => r.value);
};

/** Noms des classes d'un établissement : la fiche les rend tantôt en objets `{name}`, tantôt en simples libellés ; les vides sont écartés. */
export const classNames = (classes: Array<string | { name?: string }> | undefined): string[] =>
  (classes ?? []).map((c) => (typeof c === "string" ? c : (c?.name ?? ""))).filter((n) => n.trim().length > 0);

/** Numéro de téléphone accepté par l'annuaire : 6 à 15 chiffres, `+` ou `00` en tête, espaces, tirets et points tolérés ; vide autorisé. */
const PHONE = /^((00|\+)?(?:[0-9] ?-?\.?){6,15})?$/;
export const isValidPhone = (value: string): boolean => PHONE.test(value);

/** Identifiant (alias de connexion) : minuscules, chiffres et points, 64 caractères au plus. */
const LOGIN = /^[0-9a-z.]+$/;
export const loginError = (value: string): "empty" | "pattern" | "length" | null => {
  if (!value) return "empty";
  if (value.length > 64) return "length";
  return LOGIN.test(value) ? null : "pattern";
};

/** Le profil élève ne modifie ni son nom affiché ni ses coordonnées : la fiche est en lecture seule (hors identifiant). */
export const isStudent = (type: string[] | undefined): boolean => !!type && (type[0] === "Student" || type[0] === "ELEVE");

/** Message d'erreur d'une réponse 400 de l'annuaire (`{error: "…"}`), repéré « déjà utilisé » pour l'identifiant. */
export const apiErrorKind = (message: string | undefined): "exists" | "other" =>
  message && /already exists|existe déjà/i.test(message) ? "exists" : "other";
