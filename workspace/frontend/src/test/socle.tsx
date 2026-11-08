import { createContext, useContext, useEffect, useState } from "react";

/**
 * Équivalents minimaux des composants du socle `@open-ent/react` pour les tests : on y vérifie le comportement de
 * l'espace documentaire, pas le rendu du socle. Un menu déroulant reste replié tant qu'on ne clique pas sa commande,
 * comme dans l'interface : ses actions ne sont donc pas dans la page avant l'ouverture.
 *
 * À employer dans une fabrique `vi.mock` asynchrone :
 * `vi.mock("@open-ent/react", async () => (await import("../test/socle")).socleMock({ useEdificeClient: … }))`
 */
const OpenMenu = createContext(false);

const Dropdown = Object.assign(
  ({ children }: { children: unknown }) => {
    const [open, setOpen] = useState(false);
    return (
      <div
        onClick={(e) =>
          (e.target as HTMLElement).closest("[data-menu-toggle],[data-toolbar-new]") && setOpen((o) => !o)
        }
      >
        <OpenMenu.Provider value={open}>{children as never}</OpenMenu.Provider>
      </div>
    );
  },
  {
    Trigger: ({ label, ...rest }: { label?: unknown }) => (
      <button type="button" {...rest}>
        {label as never}
      </button>
    ),
    Menu: ({ children, ...rest }: { children: unknown }) =>
      useContext(OpenMenu) ? <div {...rest}>{children as never}</div> : null,
    Item: ({ children, ...rest }: { children: unknown }) => (
      <button type="button" {...rest}>
        {children as never}
      </button>
    ),
    Separator: () => null,
  },
);

/**
 * La vraie `Card` pose, tant que `isClickable` n'est pas faux, un bouton invisible qui couvre toute la carte
 * (« Ouvrir la resource ») et intercepte les clics ; `isSelectable` ajoute un menu « … ». Le faux les reproduit :
 * sans eux, une carte qui n'écoute plus les clics passerait les tests et ne se verrait qu'à l'écran.
 */
const Card = Object.assign(
  ({ children, isClickable, isSelectable, onClick, onSelect, ...rest }: {
    children: unknown;
    isClickable?: boolean;
    isSelectable?: boolean;
    onClick?: () => void;
    onSelect?: () => void;
  }) => (
    <div {...rest}>
      {isSelectable !== false && <button type="button" aria-label="card.open.menu" onClick={onSelect} />}
      {isClickable !== false && (
        <button type="button" aria-label="card.open.resource" data-card-overlay onClick={onClick} />
      )}
      {children as never}
    </div>
  ),
  {
    Title: ({ children }: { children: unknown }) => <div>{children as never}</div>,
    Text: ({ children }: { children: unknown }) => <div>{children as never}</div>,
    Body: ({ children }: { children: unknown }) => <div>{children as never}</div>,
    Image: () => null,
    Header: () => null,
    Footer: ({ children }: { children: unknown }) => <div>{children as never}</div>,
    User: () => null,
  },
);

const Modal = Object.assign(
  ({ children, isOpen }: { children: unknown; isOpen?: boolean }) =>
    isOpen === false ? null : <div role="dialog">{children as never}</div>,
  {
    Header: ({ children }: { children: unknown }) => <div>{children as never}</div>,
    Body: ({ children }: { children: unknown }) => <div>{children as never}</div>,
    Footer: ({ children }: { children: unknown }) => <div>{children as never}</div>,
    Subtitle: ({ children }: { children: unknown }) => <div>{children as never}</div>,
  },
);

export const socleMock = (overrides: Record<string, unknown> = {}) => ({
  Alert: ({ children, button }: { children: unknown; button?: unknown }) => (
    <div role="alert">
      {children as never}
      {button as never}
    </div>
  ),
  AppHeader: ({ children, render }: { children: unknown; render?: () => unknown }) => (
    <div>
      {children as never}
      {render ? (render() as never) : null}
    </div>
  ),
  Badge: ({ children, ...rest }: { children: unknown }) => <span {...rest}>{children as never}</span>,
  Button: ({ children, ...rest }: { children: unknown }) => (
    <button type="button" {...rest}>
      {children as never}
    </button>
  ),
  Card,
  Checkbox: (props: Record<string, unknown>) => <input type="checkbox" {...props} />,
  Dropdown,
  EmptyScreen: ({ title, text }: { title?: string; text?: string }) => (
    <div>
      <h2>{title}</h2>
      {text ? <p>{text}</p> : null}
    </div>
  ),
  ImageEditor: () => <div data-image-editor />,
  LoadingScreen: () => <div data-loading />,
  Modal,
  SearchBar: ({
    isVariant: _isVariant,
    onClick: _onClick,
    clearable: _clearable,
    ...rest
  }: Record<string, unknown>) => <input type="search" {...rest} />,
  SegmentedControl: ({ value }: { value: string }) => <div data-segmented={value} />,
  /**
   * Le vrai composant attend l'OBJET de l'option pour afficher son libellé (une chaîne s'afficherait telle
   * quelle) et annonce sa valeur courante au montage : le faux reproduit les deux, sinon la régression
   * « name-asc affiché au lieu de Nom (A → Z) » passerait inaperçue.
   */
  Select: ({
    options,
    selectedValue,
    onValueChange,
  }: {
    options: Array<{ value: string; label: string }>;
    selectedValue?: { value: string; label: string } | string;
    onValueChange?: (v: string) => void;
  }) => {
    const current = typeof selectedValue === "object" ? selectedValue : options.find((o) => o.value === selectedValue);
    useEffect(() => {
      if (current) onValueChange?.(current.value);
    }, [current?.value]);
    return (
      <select
        data-select-label={current ? current.label : String(selectedValue ?? "")}
        value={current?.value ?? ""}
        onChange={(e) => onValueChange?.(e.target.value)}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    );
  },
  ShareModal: () => null,
  SmartEllipsis: ({ text }: { text: string }) => <span>{text}</span>,
  useEdificeClient: () => ({ user: { userId: "u1" } }),
  ...overrides,
});
