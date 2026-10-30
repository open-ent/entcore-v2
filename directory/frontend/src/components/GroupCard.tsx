import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { sortVisibles, Visible, VisibleGroup } from "../lib/directory";
import { directoryService } from "../services/account.service";

/** Fiche d'un groupe : son nom et ses membres visibles ; un clic sur un membre ouvre sa fiche. */
export const GroupCard = ({ group, onBack, onOpenUser }: { group: VisibleGroup; onBack: () => void; onOpenUser: (u: Visible) => void }) => {
  const { t } = useTranslation("directory");
  const members = useQuery({ queryKey: ["group-members", group.id], queryFn: () => directoryService.groupMembers(group.id) });
  const list = sortVisibles(members.data ?? []);
  return (
    <article data-directory-group-card={group.id}>
      <button type="button" className="btn btn-link p-0 mb-16" onClick={onBack} data-directory-back>
        ← {t("userBook.directory.backToResults")}
      </button>
      <h2 className="h3">{group.name}</h2>
      {members.isSuccess && (
        <p className="text-muted" data-group-count>
          {list.length} {t("directory.one.user")}
          {list.length > 1 ? "s" : ""}
        </p>
      )}
      {members.isSuccess && list.length === 0 && <p>{t("noresult")}</p>}
      <ul className="list-unstyled d-flex flex-wrap gap-16" data-group-members>
        {list.map((u) => (
          <li key={u.id} style={{ width: 240 }}>
            <button type="button" className="btn btn-outline-secondary w-100 d-flex gap-8 align-items-center text-start" onClick={() => onOpenUser(u)} data-group-member={u.id}>
              <img src={`/userbook/avatar/${u.id}?thumbnail=100x100`} alt="" width={40} height={40} style={{ borderRadius: "50%", objectFit: "cover" }} loading="lazy" />
              <span>
                <strong className="d-block">{u.displayName}</strong>
                {u.profile && <small className="text-muted">{t(u.profile)}</small>}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </article>
  );
};
