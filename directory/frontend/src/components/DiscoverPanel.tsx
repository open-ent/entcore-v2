import { Button } from "@open-ent/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  addUsersBody,
  discoverBody,
  DiscoverGroup,
  DiscoverUser,
  leaveGroupBody,
} from "../lib/discover";
import { directoryService } from "../services/account.service";

/**
 * Découvrir : personnes d'autres établissements que l'on peut ajouter à ses relations (puis leur écrire), et groupes de
 * discussion personnels. Réservé aux profils autorisés par la plateforme.
 */
export const DiscoverPanel = ({ myId }: { myId: string }) => {
  const { t } = useTranslation("directory");
  const qc = useQueryClient();
  const [view, setView] = useState<"users" | "groups">("users");
  const [text, setText] = useState("");
  const [structure, setStructure] = useState("");
  const [submitted, setSubmitted] = useState<{
    text: string;
    structure: string;
  } | null>(null);
  const [group, setGroup] = useState<DiscoverGroup | null>(null);
  const [newGroup, setNewGroup] = useState("");

  const structures = useQuery({
    queryKey: ["discover-structures"],
    queryFn: directoryService.discoverStructures,
  });
  const users = useQuery({
    queryKey: ["discover-users", submitted],
    enabled: !!submitted && view === "users",
    queryFn: () =>
      directoryService.discoverUsers(
        discoverBody(
          submitted?.text ?? "",
          submitted?.structure ? [submitted.structure] : [],
          [],
        ),
      ),
  });
  const groups = useQuery({
    queryKey: ["discover-groups"],
    queryFn: directoryService.discoverGroups,
    enabled: view === "groups",
  });
  const members = useQuery({
    queryKey: ["discover-group-users", group?.id],
    enabled: !!group,
    queryFn: () => directoryService.discoverGroupUsers(group?.id as string),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setSubmitted({ text, structure });
  };
  const relation = async (u: DiscoverUser) => {
    if (u.hasCommunication) await directoryService.discoverRemoveRelation(u.id);
    else await directoryService.discoverAddRelation(u.id);
    await qc.invalidateQueries({ queryKey: ["discover-users"] });
  };

  return (
    <section data-directory-discover>
      <div role="tablist" className="d-flex gap-16 mb-16">
        {(["users", "groups"] as const).map((v) => (
          <button
            key={v}
            type="button"
            role="tab"
            aria-selected={view === v}
            className="btn btn-link"
            style={{ fontWeight: view === v ? 700 : 400, color: "inherit" }}
            onClick={() => {
              setView(v);
              setGroup(null);
            }}
            data-discover-view={v}
          >
            {t(v === "users" ? "share.users" : "share.groups")}
          </button>
        ))}
      </div>

      {view === "users" && (
        <>
          <form
            onSubmit={submit}
            className="d-flex gap-8 flex-wrap mb-16"
            data-discover-form
          >
            <input
              className="form-control"
              style={{ maxWidth: 320 }}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={t("userBook.search")}
              aria-label={t("userBook.search")}
              data-discover-search
            />
            <select
              className="form-select"
              style={{ maxWidth: 320 }}
              value={structure}
              onChange={(e) => setStructure(e.target.value)}
              aria-label={t("directory.search.structure")}
              data-discover-structure
            >
              <option value="">—</option>
              {(structures.data ?? []).map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
            <Button type="submit" color="primary" data-discover-submit>
              {t("directory.search")}
            </Button>
          </form>
          {users.isSuccess && users.data.length === 0 && (
            <p data-discover-noresult>{t("noresult")}</p>
          )}
          <ul
            className="list-unstyled d-flex flex-wrap gap-16"
            data-discover-results
          >
            {(users.data ?? []).map((u) => (
              <li
                key={u.id}
                className="border rounded p-8"
                style={{ width: 260 }}
                data-discover-user={u.id}
              >
                <strong className="d-block">{u.displayName}</strong>
                {u.profile && (
                  <small className="text-muted">{t(u.profile)}</small>
                )}
                <div className="d-flex gap-8 mt-8">
                  <Button
                    type="button"
                    color={u.hasCommunication ? "danger" : "primary"}
                    variant="outline"
                    onClick={() => relation(u)}
                    data-discover-relation={u.id}
                  >
                    {u.hasCommunication
                      ? t("directory.discover.remove", {
                          defaultValue: "Retirer de mes relations",
                        })
                      : t("directory.discover.add", {
                          defaultValue: "Ajouter à mes relations",
                        })}
                  </Button>
                  {u.hasCommunication && (
                    <a
                      className="btn btn-outline-primary"
                      href={`/conversation/conversation#/write-mail/${u.id}`}
                    >
                      {t("userBook.class.write-message")}
                    </a>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </>
      )}

      {view === "groups" && !group && (
        <>
          <form
            className="d-flex gap-8 mb-16"
            onSubmit={async (e) => {
              e.preventDefault();
              if (!newGroup.trim()) return;
              await directoryService.discoverCreateGroup(newGroup.trim());
              setNewGroup("");
              await qc.invalidateQueries({ queryKey: ["discover-groups"] });
            }}
            data-discover-group-form
          >
            <input
              className="form-control"
              style={{ maxWidth: 320 }}
              value={newGroup}
              onChange={(e) => setNewGroup(e.target.value)}
              aria-label={t("directory.discover.group.name", {
                defaultValue: "Nom du groupe",
              })}
              data-discover-group-name
            />
            <Button
              type="submit"
              color="primary"
              disabled={!newGroup.trim()}
              data-discover-group-create
            >
              {t("directory.discover.group.create", {
                defaultValue: "Créer un groupe",
              })}
            </Button>
          </form>
          <ul className="list-unstyled" data-discover-groups>
            {(groups.data ?? []).map((g) => (
              <li key={g.id} className="mb-8">
                <button
                  type="button"
                  className="btn btn-outline-secondary"
                  onClick={() => setGroup(g)}
                  data-discover-group={g.id}
                >
                  {g.name}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}

      {view === "groups" && group && (
        <article data-discover-group-card={group.id}>
          <button
            type="button"
            className="btn btn-link p-0 mb-16"
            onClick={() => setGroup(null)}
            data-directory-back
          >
            ← {t("userBook.directory.backToResults")}
          </button>
          <h2 className="h3">{group.name}</h2>
          <ul>
            {(members.data ?? []).map((m) => (
              <li key={m.id}>{m.displayName}</li>
            ))}
          </ul>
          <Button
            type="button"
            color="danger"
            variant="outline"
            onClick={async () => {
              const ids = (members.data ?? []).map((m) => m.id);
              await directoryService.discoverSetGroupUsers(
                group.id,
                leaveGroupBody(ids, myId),
              );
              setGroup(null);
              await qc.invalidateQueries({ queryKey: ["discover-groups"] });
            }}
            data-discover-group-leave
          >
            {t("directory.discover.group.leave", {
              defaultValue: "Quitter le groupe",
            })}
          </Button>
        </article>
      )}
    </section>
  );
};
export { addUsersBody };
