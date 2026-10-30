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
  relatedId?: string | null;
}

export interface AuthContext {
  passwordRegex: string;
  passwordRegexI18n?: Record<string, string>;
}

const xsrf = (): string => decodeURIComponent(/(?:^|; )XSRF-TOKEN=([^;]*)/.exec(document.cookie)?.[1] ?? "");

export interface Children {
  structureName: string;
  children: Array<{ id: string; displayName: string; classesNames?: string[] }>;
}

export interface Criteria {
  structures: Array<{ id: string; name: string }>;
  classes?: Array<{ id: string; name: string }>;
  functions?: string[];
  profiles?: string[];
  positions?: Array<{ id: string; name: string }>;
}

export const directoryService = {
  criteria: () => odeServices.http().get<Criteria>("/userbook/search/criteria?getClassesMonoStructureOnly=true"),
  classesOf: async (structureId: string) => (await odeServices.http().get<{ classes: Array<{ id: string; label: string }> }>(`/userbook/search/criteria/${structureId}/classes`)).classes ?? [],
  search: async (body: Record<string, unknown>) => (await odeServices.http().post<{ users: import("../lib/directory").Visible[] }>("/communication/visible", body)).users ?? [],
  searchGroups: async (body: Record<string, unknown>) => (await odeServices.http().post<{ groups: import("../lib/directory").VisibleGroup[] }>("/communication/visible", body)).groups ?? [],
  groupMembers: (id: string) => odeServices.http().get<import("../lib/directory").Visible[]>(`/communication/visible/group/${id}`),
  favorites: () => odeServices.http().get<Array<{ id: string; name: string }>>("/directory/sharebookmark/all"),
  favorite: (id: string) => odeServices.http().get<{ users?: Array<{ id: string; displayName?: string; username?: string }>; groups?: Array<{ id: string; name: string }> }>(`/directory/sharebookmark/${id}`),
  createFavorite: (body: { name: string; members: string[] }) => odeServices.http().post<{ id: string }>("/directory/sharebookmark", body),
  updateFavorite: (id: string, body: { name: string; members: string[] }) => odeServices.http().put<unknown>(`/directory/sharebookmark/${id}`, body),
  deleteFavorite: (id: string) => odeServices.http().delete<unknown>(`/directory/sharebookmark/${id}`),
  searchMembers: async (search: string) => {
    const res = await odeServices.http().post<{ users: Array<{ id: string; displayName: string }>; groups: Array<{ id: string; name: string }> }>("/communication/visible", { search: search.trim().toLowerCase(), types: ["User", "Group"] });
    return res;
  },
  discoverProfiles: async () => {
    try {
      return await odeServices.http().get<Array<{ type: string | null }>>("/communication/discover/visible/profiles");
    } catch {
      return [];
    }
  },
  discoverStructures: () => odeServices.http().get<Array<{ id: string; name: string }>>("/communication/discover/visible/structures"),
  discoverUsers: (body: Record<string, unknown>) => odeServices.http().post<Array<import("../lib/discover").DiscoverUser>>("/communication/discover/visible/users", body),
  discoverGroups: () => odeServices.http().get<Array<import("../lib/discover").DiscoverGroup>>("/communication/discover/visible/groups"),
  discoverGroupUsers: (id: string) => odeServices.http().get<Array<import("../lib/discover").DiscoverUser>>(`/communication/discover/visible/group/${id}/users`),
  discoverAddRelation: (id: string) => odeServices.http().post<unknown>(`/communication/discover/visible/add/commuting/${id}`),
  discoverRemoveRelation: (id: string) => odeServices.http().delete<unknown>(`/communication/discover/visible/remove/commuting/${id}`),
  discoverCreateGroup: (name: string) => odeServices.http().post<unknown>("/communication/discover/visible/group", { name }),
  discoverSetGroupUsers: (id: string, body: { oldUsers: string[]; newUsers: string[] }) => odeServices.http().put<unknown>(`/communication/discover/visible/group/${id}/users`, body),
  /** Fiche d'un utilisateur visible : ce que son profil laisse voir (coordonnées si rendues publiques, centres d'intérêt, devise). */
  person: async (id: string, type: string) => {
    const res = await odeServices.http().get<{ result: Person[] }>(`/userbook/api/person?id=${id}&type=${type}`);
    return res.result?.[0];
  },
};

export const accountService = {
  themeConf: async (): Promise<string> => (await fetch("/assets/theme-conf.js", { credentials: "same-origin" })).text(),
  currentTheme: async (): Promise<string> => (await odeServices.http().get<{ themeName: string }>("/theme")).themeName,
  themePreference: async (): Promise<string | null> => (await odeServices.http().get<{ preference: string | null }>("/userbook/preference/theme")).preference,
  /** Le serveur attend le nom du thème tel quel, pas un objet JSON. */
  setThemePreference: async (name: string): Promise<void> => {
    const res = await fetch("/userbook/preference/theme", {
      method: "PUT",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": xsrf() },
      body: name,
    });
    if (!res.ok) throw new Error("theme");
  },
  children: (id: string) => odeServices.http().get<Children[]>(`/directory/user/${id}/children`),
  /** Devise, humeur, centres d'intérêt, divers : seules les clés envoyées changent. */
  saveUserbook: (id: string, body: { motto?: string; mood?: string; health?: string; hobbies?: Array<{ category: string; values?: string; visibility?: string }> }) =>
    odeServices.http().put<unknown>(`/directory/userbook/${id}`, body),
  setHobbyVisibility: (category: string, value: "PUBLIC" | "PRIVE") =>
    odeServices.http().get<unknown>(`/userbook/api/set-visibility?value=${value}&category=${encodeURIComponent(category)}`),
  setInfoVisibility: (info: string, state: "public" | "prive") =>
    odeServices.http().get<unknown>(`/userbook/api/edit-user-info-visibility?info=${info}&state=${state}`),
  moods: () => odeServices.http().get<string[]>("/directory/userbook/moods"),
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
