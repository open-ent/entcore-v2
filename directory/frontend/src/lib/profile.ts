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
