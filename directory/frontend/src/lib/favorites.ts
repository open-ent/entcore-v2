export interface Member {
  id: string;
  /** « group » pour un groupe, « user » pour une personne */
  kind: "user" | "group";
  name: string;
}

export interface Favorite {
  id: string;
  name: string;
}

export const FAVORITE_NAME_MAX = 60;

/** Un favori a besoin d'un nom (non vide, 60 caractères au plus) et d'au moins un membre. */
export const favoriteProblem = (name: string, members: Member[]): "name" | "members" | null => {
  const n = name.trim();
  if (!n || n.length > FAVORITE_NAME_MAX) return "name";
  return members.length === 0 ? "members" : null;
};

/** Ajoute un membre s'il n'y est pas déjà (même identifiant). */
export const addMember = (members: Member[], member: Member): Member[] => (members.some((m) => m.id === member.id) ? members : [...members, member]);

export const removeMember = (members: Member[], id: string): Member[] => members.filter((m) => m.id !== id);

/** Corps de création / modification : le nom et les identifiants des membres. */
export const favoriteBody = (name: string, members: Member[]) => ({ name: name.trim(), members: members.map((m) => m.id) });

/** Membres d'un favori à partir de la réponse du serveur (`{users, groups}`). */
export const membersOf = (data: { users?: Array<{ id: string; displayName?: string; username?: string }>; groups?: Array<{ id: string; name: string }> }): Member[] => [
  ...(data.groups ?? []).map((g) => ({ id: g.id, kind: "group" as const, name: g.name })),
  ...(data.users ?? []).map((u) => ({ id: u.id, kind: "user" as const, name: u.displayName ?? u.username ?? "" })),
];
