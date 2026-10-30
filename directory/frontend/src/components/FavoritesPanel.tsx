import { Button } from "@open-ent/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  addMember,
  FAVORITE_NAME_MAX,
  favoriteBody,
  favoriteProblem,
  Member,
  membersOf,
  removeMember,
} from "../lib/favorites";
import { directoryService } from "../services/account.service";

type Editing = { id: string | null; name: string; members: Member[] } | null;

/** Favoris de partage : listes nommées de personnes et de groupes, réutilisées dans les fenêtres de partage et la messagerie. */
export const FavoritesPanel = () => {
  const { t } = useTranslation("directory");
  const qc = useQueryClient();
  const list = useQuery({
    queryKey: ["favorites"],
    queryFn: directoryService.favorites,
  });
  const [editing, setEditing] = useState<Editing>(null);
  const [opened, setOpened] = useState<{ id: string; name: string } | null>(
    null,
  );
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const openedMembers = useQuery({
    queryKey: ["favorite", opened?.id],
    enabled: !!opened,
    queryFn: async () =>
      membersOf(await directoryService.favorite(opened?.id as string)),
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["favorites"] });

  const startEdit = async (f: { id: string; name: string } | null) => {
    if (!f) return setEditing({ id: null, name: "", members: [] });
    setEditing({
      id: f.id,
      name: f.name,
      members: membersOf(await directoryService.favorite(f.id)),
    });
  };

  if (editing) {
    return (
      <FavoriteForm
        editing={editing}
        error={error}
        onCancel={() => {
          setEditing(null);
          setError(null);
        }}
        onSave={async (name, members) => {
          try {
            const body = favoriteBody(name, members);
            if (editing.id)
              await directoryService.updateFavorite(editing.id, body);
            else await directoryService.createFavorite(body);
            await refresh();
            setEditing(null);
            setError(null);
          } catch {
            setError(
              t("account.error.save", {
                defaultValue: "L'enregistrement a échoué.",
              }),
            );
          }
        }}
      />
    );
  }

  if (opened) {
    return (
      <article data-favorite-card={opened.id}>
        <button
          type="button"
          className="btn btn-link p-0 mb-16"
          onClick={() => setOpened(null)}
          data-directory-back
        >
          ← {t("userBook.directory.backToResults")}
        </button>
        <h2 className="h3">{opened.name}</h2>
        <ul data-favorite-members>
          {(openedMembers.data ?? []).map((m) => (
            <li key={m.id}>
              {m.kind === "group" ? "👥 " : ""}
              {m.name}
            </li>
          ))}
        </ul>
        <Button
          type="button"
          color="primary"
          variant="outline"
          onClick={() => startEdit(opened)}
          data-favorite-edit
        >
          {t("userBook.directory.button.edit.favorite")}
        </Button>
      </article>
    );
  }

  return (
    <section data-directory-favorites>
      <Button
        type="button"
        color="primary"
        onClick={() => startEdit(null)}
        data-favorite-create
      >
        {t("directory.favorites.create", { defaultValue: "Créer un favori" })}
      </Button>
      {list.isSuccess && list.data.length === 0 && (
        <div className="text-center text-muted mt-24" data-favorites-empty>
          <h2 className="h4">
            {t("userBook.directory.emptyScreen.favorite.header")}
          </h2>
        </div>
      )}
      <ul className="list-unstyled mt-16" data-favorites-list>
        {(list.data ?? []).map((f) => (
          <li
            key={f.id}
            className="d-flex gap-8 align-items-center mb-8"
            data-favorite={f.id}
          >
            <button
              type="button"
              className="btn btn-outline-secondary flex-grow-1 text-start"
              onClick={() => setOpened(f)}
              data-favorite-open={f.id}
            >
              ⭐ {f.name}
            </button>
            {confirmDelete === f.id ? (
              <>
                <span>
                  {t("directory.favorites.delete.confirm", {
                    defaultValue: "Supprimer ce favori ?",
                  })}
                </span>
                <Button
                  type="button"
                  color="danger"
                  onClick={async () => {
                    await directoryService.deleteFavorite(f.id);
                    setConfirmDelete(null);
                    await refresh();
                  }}
                  data-favorite-delete-confirm
                >
                  {t("userBook.remove", { defaultValue: "Supprimer" })}
                </Button>
                <Button
                  type="button"
                  color="tertiary"
                  variant="ghost"
                  onClick={() => setConfirmDelete(null)}
                >
                  {t("cancel")}
                </Button>
              </>
            ) : (
              <Button
                type="button"
                color="tertiary"
                variant="ghost"
                onClick={() => setConfirmDelete(f.id)}
                data-favorite-delete={f.id}
              >
                {t("userBook.remove", { defaultValue: "Supprimer" })}
              </Button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
};

/** Création / modification d'un favori : nom, puis recherche de personnes et de groupes à y ajouter. */
const FavoriteForm = ({
  editing,
  error,
  onCancel,
  onSave,
}: {
  editing: NonNullable<Editing>;
  error: string | null;
  onCancel: () => void;
  onSave: (name: string, members: Member[]) => void;
}) => {
  const { t } = useTranslation("directory");
  const [name, setName] = useState(editing.name);
  const [members, setMembers] = useState<Member[]>(editing.members);
  const [query, setQuery] = useState("");
  const [found, setFound] = useState<Member[]>([]);
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setFound([]);
      return;
    }
    const timer = window.setTimeout(async () => {
      const res = await directoryService.searchMembers(q);
      setFound(membersOf(res).slice(0, 30));
    }, 300);
    return () => window.clearTimeout(timer);
  }, [query]);
  const problem = favoriteProblem(name, members);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!problem) onSave(name, members);
  };
  return (
    <form onSubmit={submit} data-favorite-form>
      <label className="d-block mb-16">
        {t("directory.favorites.name", { defaultValue: "Nom du favori" })}
        <input
          className="form-control"
          value={name}
          maxLength={FAVORITE_NAME_MAX}
          onChange={(e) => setName(e.target.value)}
          data-favorite-name
        />
      </label>
      <label className="d-block mb-8">
        {t("directory.search.favorite")}
        <input
          className="form-control"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          data-favorite-search
        />
      </label>
      {found.length > 0 && (
        <ul
          className="list-unstyled border rounded p-8 mb-16"
          data-favorite-found
        >
          {found.map((m) => (
            <li key={m.id}>
              <button
                type="button"
                className="btn btn-link p-0"
                onClick={() => setMembers(addMember(members, m))}
                data-favorite-add={m.id}
              >
                + {m.kind === "group" ? "👥 " : ""}
                {m.name}
              </button>
            </li>
          ))}
        </ul>
      )}
      <h3 className="h6">{t("directory.search.favorite.add")}</h3>
      <ul
        className="d-flex flex-wrap gap-8 list-unstyled"
        data-favorite-selected
      >
        {members.map((m) => (
          <li
            key={m.id}
            className="badge bg-light text-dark d-flex gap-8 align-items-center"
          >
            {m.kind === "group" ? "👥 " : ""}
            {m.name}
            <button
              type="button"
              className="btn btn-link p-0"
              aria-label={t("userBook.remove", { defaultValue: "Supprimer" })}
              onClick={() => setMembers(removeMember(members, m.id))}
              data-favorite-remove={m.id}
            >
              ×
            </button>
          </li>
        ))}
      </ul>
      {error && (
        <p className="text-danger" role="alert" data-favorite-error>
          {error}
        </p>
      )}
      <div className="d-flex gap-8 mt-16">
        <Button
          type="button"
          color="tertiary"
          variant="ghost"
          onClick={onCancel}
        >
          {t("cancel")}
        </Button>
        <Button
          type="submit"
          color="primary"
          disabled={!!problem}
          data-favorite-save
        >
          {t("directory.save")}
        </Button>
      </div>
    </form>
  );
};
