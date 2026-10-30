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

  const enfant = { _id: "f2", name: "6e", eType: "folder", eParent: "f1", owner: "u1" } as WorkspaceElement;

  it("mode lazy : un dossier dont les enfants ne sont pas chargés se déplie et déclenche le chargement", () => {
    const onExpand = vi.fn();
    const { container } = show({ lazy: { isLoaded: () => false, onExpand } });
    const toggle = container.querySelector("[data-tree-toggle]") as HTMLElement;
    expect(toggle).not.toBeNull();
    fireEvent.click(toggle);
    expect(onExpand).toHaveBeenCalledWith("f1");
  });

  it("mode lazy : plus de flèche une fois les enfants chargés et absents", () => {
    const { container } = show({ lazy: { isLoaded: () => true, onExpand: vi.fn() } });
    expect(container.querySelector("[data-tree-toggle]")).toBeNull();
  });

  it("hors mode lazy : aucune flèche pour un dossier sans enfant, et aucun chargement au dépliage d'un dossier qui en a", () => {
    const onExpand = vi.fn();
    expect(show({}).container.querySelector("[data-tree-toggle]")).toBeNull();
    const { container } = show({ folders: [dossier, enfant] });
    fireEvent.click(container.querySelector("[data-tree-toggle]") as HTMLElement);
    expect(container.querySelector('[data-tree-folder="f2"]')).not.toBeNull();
    expect(onExpand).not.toHaveBeenCalled();
  });

  it("flèches : → déplie, ↓ descend, ← remonte", () => {
    const { container } = show({ folders: [dossier, enfant] });
    const lien = (id: string) => container.querySelector(`[data-tree-folder="${id}"]`) as HTMLElement;
    lien("f1").focus();
    fireEvent.keyDown(lien("f1"), { key: "ArrowRight" });
    expect(lien("f2")).not.toBeNull();
    fireEvent.keyDown(lien("f1"), { key: "ArrowDown" });
    expect(document.activeElement).toBe(lien("f2"));
    fireEvent.keyDown(lien("f2"), { key: "ArrowLeft" });
    expect(document.activeElement).toBe(lien("f1"));
  });

  it("F2 renomme et Suppr supprime le dossier ayant le focus ; sans `keys`, rien", () => {
    const keys = { onRename: vi.fn(), onDelete: vi.fn() };
    const { container } = show({ keys });
    const lien = container.querySelector('[data-tree-folder="f1"]') as HTMLElement;
    fireEvent.keyDown(lien, { key: "F2" });
    fireEvent.keyDown(lien, { key: "Delete" });
    expect(keys.onRename).toHaveBeenCalledWith("f1");
    expect(keys.onDelete).toHaveBeenCalledWith("f1");
    const sans = show({}).container.querySelector('[data-tree-folder="f1"]') as HTMLElement;
    fireEvent.keyDown(sans, { key: "Delete" });
    expect(keys.onDelete).toHaveBeenCalledTimes(1);
  });

  it("fichiers du poste lâchés sur un dossier : envoyés dans ce dossier ; sur « Mes documents » : racine", () => {
    const onDrop = vi.fn();
    const { container } = show({ drop: { canDrop: () => false, onDrop: vi.fn(), files: { onDrop } } });
    const ligne = (container.querySelector('[data-tree-folder="f1"]') as HTMLElement).parentElement as HTMLElement;
    fireEvent.dragOver(ligne, { dataTransfer: { types: ["Files"] } });
    expect(ligne.getAttribute("data-drop-target")).toBe("true");
    fireEvent.drop(ligne, { dataTransfer: { types: ["Files"], files: [] } });
    expect(onDrop).toHaveBeenLastCalledWith("f1", expect.anything());
    const racine = (container.querySelector('[data-tree-root="owner"]') as HTMLElement).parentElement as HTMLElement;
    fireEvent.drop(racine, { dataTransfer: { types: ["Files"], files: [] } });
    expect(onDrop).toHaveBeenLastCalledWith(null, expect.anything());
  });
});
