import { WorkspaceElement } from "./types";

/**
 * Entrée du partage multiple du socle (`shareOptions.resources`) pour chaque élément sélectionné, avec les droits PROPRES
 * de l'usager (`user:<id>:<action>`) : le socle les réinjecte dans la liste enregistrée, sinon un gestionnaire non
 * propriétaire se retirerait ses propres droits. Le propriétaire n'a pas d'entrée de partage : liste vide.
 * Ex. fichier f1 partagé { userId: "u1", "…|getDocument": true } → { resourceId: "f1", resourceRights: ["user:u1:…|getDocument"] }.
 */
export const shareResources = (elements: WorkspaceElement[], userId: string | undefined) =>
  elements.map((e) => ({
    resourceId: e._id,
    resourceRights: userId
      ? (e.shared ?? [])
          .filter((s) => s.userId === userId)
          .flatMap((s) =>
            Object.entries(s)
              .filter(([key, value]) => key !== "userId" && value === true)
              .map(([action]) => `user:${userId}:${action}`),
          )
      : [],
  }));
