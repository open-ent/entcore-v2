/** Une ligne visible de l'arbre : niveau d'imbrication (1 = racine), dépliable ou non, déplié ou non. */
export interface TreeRow {
    level: number;
    expandable: boolean;
    expanded: boolean;
}

export type TreeMove = { focus: number } | { toggle: "open" | "close" } | null;

/**
 * Déplacement demandé par une touche fléchée dans l'arbre (modèle ARIA « tree », comme dans la nouvelle interface) :
 *  - ↓ / ↑ : ligne suivante / précédente ; Début / Fin : première / dernière ;
 *  - → : déplie un dossier replié, sinon va sur son premier sous-dossier ;
 *  - ← : replie un dossier déplié, sinon remonte au dossier parent.
 * Ex. lignes [Mes documents(1), Cours(2, plié), Archives(2)], position 1 + « → » → déplier ; position 2 + « ← » → ligne 0.
 */
export const treeMove = (key: string, rows: TreeRow[], index: number): TreeMove => {
    const row = rows[index];
    if (!row) return null;
    const last = rows.length - 1;
    switch (key) {
        case "ArrowDown":
            return index < last ? { focus: index + 1 } : null;
        case "ArrowUp":
            return index > 0 ? { focus: index - 1 } : null;
        case "Home":
            return index > 0 ? { focus: 0 } : null;
        case "End":
            return index < last ? { focus: last } : null;
        case "ArrowRight":
            if (row.expandable && !row.expanded) return { toggle: "open" };
            return row.expanded && rows[index + 1] && rows[index + 1].level > row.level ? { focus: index + 1 } : null;
        case "ArrowLeft": {
            if (row.expandable && row.expanded) return { toggle: "close" };
            for (let i = index - 1; i >= 0; i--) if (rows[i].level < row.level) return { focus: i };
            return null;
        }
        default:
            return null;
    }
};
