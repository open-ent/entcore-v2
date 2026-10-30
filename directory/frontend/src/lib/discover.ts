export interface AcceptedProfile {
  type: string | null;
}

/** « Découvrir » est proposé aux enseignants et aux personnels si leur profil figure parmi les profils acceptés par la plateforme. */
export const discoverAllowed = (myType: string | undefined, accepted: AcceptedProfile[]): boolean => {
  const mine = myType === "Teacher" || myType === "ENSEIGNANT" ? "Teacher" : myType === "Personnel" || myType === "PERSEDUCNAT" ? "Personnel" : null;
  return !!mine && accepted.some((p) => p.type === mine);
};

export const discoverBody = (search: string, structures: string[], profiles: string[]) => {
  const body: Record<string, unknown> = { search: search.trim().toLowerCase() };
  if (structures.length > 0) body.structures = structures;
  if (profiles.length > 0) body.profiles = profiles;
  return body;
};

export interface DiscoverUser {
  id: string;
  displayName: string;
  profile?: string;
  hasCommunication?: boolean;
}

export interface DiscoverGroup {
  id: string;
  name: string;
}

/** Corps d'ajout de membres : les anciens (à conserver) et les nouveaux (anciens + ajoutés, sans doublon). */
export const addUsersBody = (oldIds: string[], added: string[]) => ({ oldUsers: oldIds, newUsers: Array.from(new Set([...oldIds, ...added])) });

/** Quitter un groupe : on renvoie la liste privée de soi-même. */
export const leaveGroupBody = (oldIds: string[], me: string) => ({ oldUsers: oldIds, newUsers: oldIds.filter((id) => id !== me) });
