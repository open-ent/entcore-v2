import { fireEvent, render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { TreeNav } from "./TreeNav";
import { WorkspaceElement } from "../lib/types";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const dossier = { _id: "f1", name: "Cours", eType: "folder", eParent: null, owner: "u1" } as WorkspaceElement;
const show = (props: Record<string, unknown>) =>
  render(
    <MemoryRouter>
      <TreeNav tree="owner" folders={[dossier]} {...props} />
    </MemoryRouter>,
  );

describe("TreeNav", () => {
  it("affiche les dossiers de « Documents personnels » quand il est l'arbre courant", () => {
    const { container } = show({});
    expect(container.querySelector('[data-tree-folder="f1"]')).not.toBeNull();
  });

  it("sur la page NextCloud (aucun emplacement sélectionné), garde les dossiers dépliés avec keepOpen", () => {
    expect(show({ highlight: false }).container.querySelector('[data-tree-folder="f1"]')).toBeNull();
    expect(show({ highlight: false, keepOpen: true }).container.querySelector('[data-tree-folder="f1"]')).not.toBeNull();
  });

  it("lâcher des éléments sur « Corbeille » les y met", () => {
    const onDrop = vi.fn();
    const { container } = show({ drop: { canDrop: () => true, onDrop: vi.fn(), trash: { canDrop: () => true, onDrop } } });
    const trash = container.querySelector('[data-tree-root="trash"]')!.parentElement as HTMLElement;
    fireEvent.dragOver(trash, { dataTransfer: { types: ["application/x-workspace-elements"] } });
    expect(trash.getAttribute("data-drop-target")).toBe("true");
    fireEvent.drop(trash, { dataTransfer: { types: ["application/x-workspace-elements"] } });
    expect(onDrop).toHaveBeenCalledTimes(1);
  });

  it("ne réagit pas au lâcher quand ce n'est pas permis", () => {
    const onDrop = vi.fn();
    const { container } = show({ drop: { canDrop: () => true, onDrop: vi.fn(), trash: { canDrop: () => false, onDrop } } });
    const trash = container.querySelector('[data-tree-root="trash"]')!.parentElement as HTMLElement;
    fireEvent.drop(trash, { dataTransfer: { types: [] } });
    expect(onDrop).not.toHaveBeenCalled();
  });
});
