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

export interface AuthContext {
  passwordRegex: string;
  passwordRegexI18n?: Record<string, string>;
}

const xsrf = (): string => decodeURIComponent(/(?:^|; )XSRF-TOKEN=([^;]*)/.exec(document.cookie)?.[1] ?? "");

export const accountService = {
  authContext: () => odeServices.http().get<AuthContext>("/auth/context"),
  /**
   * Change le mot de passe (formulaire classique du module d'authentification). Le serveur répond 200 dans tous les cas :
   * un échec est une réponse `{error: {message}}`, rendue ici par une exception portant ce message.
   */
  changePassword: async (login: string, oldPassword: string, password: string, confirmPassword: string): Promise<void> => {
    const res = await fetch("/auth/changePassword", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/x-www-form-urlencoded", "X-XSRF-TOKEN": xsrf(), Accept: "application/json" },
      body: new URLSearchParams({ oldPassword, password, confirmPassword, login, callback: "/userbook/mon-compte" }),
    });
    const text = await res.text();
    let body: { error?: { message?: string } | string } | null = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = null;
    }
    if (!res.ok || body?.error) {
      const e = body?.error;
      throw new Error(typeof e === "string" ? e : (e?.message ?? "error"));
    }
  },
  /** Mot de passe à usage unique pour l'application mobile (valable 10 minutes). */
  generateOtp: () => odeServices.http().post<{ otp: string }>("/auth/generate/otp"),
  /**
   * Dépose une nouvelle photo de profil en deux temps, comme le sélecteur de médias de l'ancienne interface : le fichier est
   * d'abord envoyé à l'espace documentaire (document protégé, avec ses miniatures), puis la fiche pointe sur ce document.
   */
  uploadAvatar: async (id: string, file: File): Promise<void> => {
    const form = new FormData();
    form.append("file", file);
    const thumbs = ["100x100", "290x290", "120x120", "381x381", "82x82", "48x48"].map((t) => `thumbnail=${t}`).join("&");
    const up = await fetch(`/workspace/document?protected=true&application=media-library&quality=1&${thumbs}`, {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-XSRF-TOKEN": xsrf() },
      body: form,
    });
    if (!up.ok) throw new Error("avatar");
    const doc = (await up.json()) as { _id: string };
    await odeServices.http().put<unknown>(`/directory/avatar/${id}`, { picture: `/workspace/document/${doc._id}` });
  },
  removeAvatar: (id: string) => odeServices.http().put<unknown>(`/directory/userbook/${id}`, { picture: "" }),
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
