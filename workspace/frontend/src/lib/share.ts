/** Droits de partage d'un élément tels que les renvoie `GET /workspace/share/json/:id` (partie utile). */
export interface ShareJson {
  users?: { checked?: Record<string, string[]> };
  groups?: { checked?: Record<string, string[]> };
}

export interface SharePair {
  kind: "user" | "group";
  id: string;
  actions: string[];
}

/**
 * Personnes et groupes ayant des droits, avec leurs actions : de quoi recopier le partage d'un élément sur d'autres.
 * Ex. `{ users: { checked: { u1: ["a", "b"] } }, groups: { checked: { g1: ["a"] } } }`
 *  → [{ kind: "user", id: "u1", actions: ["a", "b"] }, { kind: "group", id: "g1", actions: ["a"] }].
 */
export const sharePairs = (json: ShareJson | undefined): SharePair[] => {
  const of = (kind: SharePair["kind"], checked?: Record<string, string[]>): SharePair[] =>
    Object.entries(checked ?? {})
      .filter(([, actions]) => Array.isArray(actions) && actions.length > 0)
      .map(([id, actions]) => ({ kind, id, actions }));
  return [...of("user", json?.users?.checked), ...of("group", json?.groups?.checked)];
};

/** Corps du formulaire d'un partage : `userId` ou `groupId`, puis une entrée `actions` par droit. */
export const shareForm = (pair: SharePair): URLSearchParams => {
  const form = new URLSearchParams();
  form.append(pair.kind === "user" ? "userId" : "groupId", pair.id);
  pair.actions.forEach((a) => form.append("actions", a));
  return form;
};
