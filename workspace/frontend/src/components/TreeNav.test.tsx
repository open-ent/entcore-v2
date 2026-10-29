import { render } from "@testing-library/react";
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
});
