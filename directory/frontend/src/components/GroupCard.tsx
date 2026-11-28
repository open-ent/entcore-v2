import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { sortVisibles, Visible, VisibleGroup } from "../lib/directory";
import { directoryService } from "../services/account.service";
import { LoadingScreen } from "@open-ent/react";
import { AddressBook, GroupMark, PersonEntry } from "./AddressBook";

/** Fiche d'un groupe : son nom et ses membres visibles ; un clic sur un membre ouvre sa fiche. */
export const GroupCard = ({ group, onBack, onOpenUser }: { group: VisibleGroup; onBack: () => void; onOpenUser: (u: Visible) => void }) => {
  const { t } = useTranslation("directory");
  const members = useQuery({ queryKey: ["group-members", group.id], queryFn: () => directoryService.groupMembers(group.id) });
  const list = sortVisibles(members.data ?? []);
  return (
    <article data-directory-group-card={group.id}>
      <button type="button" className="ann-back" onClick={onBack} data-directory-back>
        ← {t("userBook.directory.backToResults")}
      </button>
      <header className="ann-card-head">
        <GroupMark />
        <div>
          <h2>{group.name}</h2>
          {members.isSuccess && (
            <span className="ann-subjects" data-group-count>
              {t("directory.count.people", { count: list.length })}
            </span>
          )}
        </div>
      </header>
      {members.isLoading && <LoadingScreen position={false} />}
      {members.isSuccess && list.length === 0 && <p className="ann-empty">{t("noresult")}</p>}
      {list.length > 0 && (
        <AddressBook
          items={list}
          nameOf={(u) => u.displayName}
          idPrefix="ann-members"
          letterTag="h3"
          data-group-members=""
          renderItem={(u) => <PersonEntry user={u} onOpen={onOpenUser} data-group-member={u.id} />}
        />
      )}
    </article>
  );
};
