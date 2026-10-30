import { odeServices } from "@open-ent/client";

/** Fiche « informations » de l'annuaire (GET /directory/user/:id). */
export interface UserInfos {
  id: string;
  login?: string;
  loginAlias?: string;
  firstName?: string;
  lastName?: string;
  displayName?: string;
  email?: string;
  mobile?: string;
  homePhone?: string;
  birthDate?: string;
  profiles?: string[];
  functions?: Array<[string, string[]]>;
}

/** Fiche « userbook » (GET /userbook/api/person) : devise, humeur, centres d'intérêt, établissements et classes. */
export interface Person {
  id: string;
  userId: string;
  login: string;
  displayName: string;
  type: string[];
  photo?: string;
  motto?: string;
  mood?: string;
  health?: string;
  address?: string | null;
  email?: string | null;
  tel?: string | null;
  mobile?: string | null;
  birthdate?: string | null;
  schools?: Array<{ id: string; name: string; classes?: Array<string | { id?: string; name?: string }> }>;
  visibleInfos?: string[];
  hobbies?: Array<{ category: string; values?: string; visibility?: string }>;
  relatedName?: string | null;
}

export const accountService = {
  /** Met à jour une partie de la fiche : seuls les champs envoyés changent. */
  update: (id: string, body: Partial<Pick<UserInfos, "displayName" | "homePhone" | "loginAlias" | "mobile" | "email">>) =>
    odeServices.http().put<unknown>(`/directory/user/${id}`, body),
  userId: async () => (await odeServices.http().get<{ userId: string }>("/auth/oauth2/userinfo")).userId,
  infos: (id: string) => odeServices.http().get<UserInfos>(`/directory/user/${id}`),
  person: async () => {
    const res = await odeServices.http().get<{ status?: string; result: Person[] }>("/userbook/api/person");
    return res.result?.[0];
  },
};
