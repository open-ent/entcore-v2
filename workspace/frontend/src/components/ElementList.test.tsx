import { fireEvent, render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ElementList } from "./ElementList";
import { WorkspaceElement } from "../lib/types";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const el = (p: Partial<WorkspaceElement>): WorkspaceElement => ({ _id: "x", name: "n.txt", eType: "file", eParent: null, owner: "u9", ownerName: "Marc", ...p });
const show = (elements: WorkspaceElement[], props: Record<string, unknown> = {}, tree = "owner") =>
  render(
    <MemoryRouter>
      <ElementList tree={tree as never} elements={elements} view="list" {...props} />
    </MemoryRouter>,
  );

describe("ElementList — vue liste", () => {
  it("le propriétaire est un lien vers l'annuaire", () => {
    const { container } = show([el({})]);
    expect(container.querySelector("[data-owner-link]")?.getAttribute("href")).toBe("/userbook/annuaire#u9");
  });

  it("une pastille indique le nombre de commentaires et ouvre les commentaires", () => {
    const onShowComments = vi.fn();
    const { container } = show([el({ _id: "c", comments: [{ id: "1" }, { id: "2" }] as never })], { onShowComments });
    const badge = container.querySelector("[data-badge-comments]")!;
    expect(badge.textContent).toBe("2");
    fireEvent.click(badge);
    expect(onShowComments).toHaveBeenCalledWith(expect.objectContaining({ _id: "c" }));
  });

  it("pas de pastille sans commentaire, ni dans la corbeille", () => {
    expect(show([el({})]).container.querySelector("[data-badge-comments]")).toBeNull();
    expect(show([el({ comments: [{ id: "1" }] as never })], {}, "trash").container.querySelector("[data-badge-comments]")).toBeNull();
  });

  it("signale un document publié sur le portail public", () => {
    const { container } = show([el({ _id: "p" }), el({ _id: "q", portalPublication: { publishedAt: "2026-10-01" } })], { publishedIds: ["p"] });
    expect(container.querySelectorAll("[data-badge-published]")).toHaveLength(2);
    expect(show([el({})]).container.querySelector("[data-badge-published]")).toBeNull();
  });

  it("signale un élément partagé dans les résultats de recherche seulement", () => {
    const shared = el({ shared: [{ userId: "u1" }] });
    expect(show([shared], { searching: true }).container.querySelector("[data-badge-shared]")).not.toBeNull();
    expect(show([shared]).container.querySelector("[data-badge-shared]")).toBeNull();
  });

});

describe("ElementList — vue en icônes", () => {
  const icons = (elements: WorkspaceElement[], props: Record<string, unknown> = {}, tree = "owner") =>
    render(
      <MemoryRouter>
        <ElementList tree={tree as never} elements={elements} view="icons" {...props} />
      </MemoryRouter>,
    );
  it("la pastille de commentaires est dans la bande du nom, et ouvre les commentaires", () => {
    const onShowComments = vi.fn();
    const { container } = icons([el({ _id: "c", comments: [{ id: "1" }] as never })], { onShowComments });
    const badge = container.querySelector("[data-element-id='c'] [data-badge-comments]")!;
    expect(badge.textContent).toBe("1");
    fireEvent.click(badge);
    expect(onShowComments).toHaveBeenCalledWith(expect.objectContaining({ _id: "c" }));
  });
  it("cliquer la pastille ne coche pas la tuile", () => {
    const onToggle = vi.fn();
    const { container } = icons([el({ _id: "c", comments: [{ id: "1" }] as never })], { onToggle });
    fireEvent.click(container.querySelector("[data-badge-comments]")!);
    expect(onToggle).not.toHaveBeenCalled();
  });
  it("pas de pastille sans commentaire ni dans la corbeille", () => {
    expect(icons([el({})]).container.querySelector("[data-badge-comments]")).toBeNull();
    expect(icons([el({ comments: [{ id: "1" }] as never })], {}, "trash").container.querySelector("[data-badge-comments]")).toBeNull();
  });
});
