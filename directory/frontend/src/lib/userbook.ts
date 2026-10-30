import { Person } from "../services/account.service";

/** Informations dont la visibilité se règle, et la clé `SHOW_…` qui les porte dans la fiche. */
export const VISIBLE_KEYS = { email: "SHOW_EMAIL", mail: "SHOW_MAIL", phone: "SHOW_PHONE", birthdate: "SHOW_BIRTHDATE", health: "SHOW_HEALTH", mobile: "SHOW_MOBILE" } as const;
export type VisibleInfo = keyof typeof VISIBLE_KEYS;

/** Ligne du profil → information dont on règle la visibilité. */
export const ROW_VISIBILITY: Record<string, VisibleInfo> = { email: "email", mobile: "mobile", tel: "phone", birthDate: "birthdate" };

export const isVisible = (person: Person | undefined, info: VisibleInfo): boolean => (person?.visibleInfos ?? []).includes(VISIBLE_KEYS[info]);

export const nextVisibility = (visible: boolean): "public" | "prive" => (visible ? "prive" : "public");

/** Une visibilité de centre d'intérêt est « PUBLIC » ou « PRIVE » (autre valeur, vide comprise : privé). */
export const hobbyIsPublic = (visibility: string | undefined): boolean => (visibility ?? "").toLowerCase() === "public";
export const toggledHobbyVisibility = (visibility: string | undefined): "PUBLIC" | "PRIVE" => (hobbyIsPublic(visibility) ? "PRIVE" : "PUBLIC");

export const MOTTO_MAX = 75;

/** La devise ne s'enregistre qu'en cliquant sur « Publier », et seulement si elle a changé. */
export const mottoChanged = (current: string, published: string): boolean => current !== published;

export interface ChildrenStructure {
  structureName: string;
  children: Array<{ id: string; displayName: string; classesNames?: string[] }>;
}

/** Profils qui voient leurs enfants (parent, enseignant, personnel) ; l'élève voit ses responsables. */
export const showsChildren = (type: string | undefined): boolean => type === "PERSRELELEVE" || type === "ENSEIGNANT" || type === "PERSEDUCNAT";
export const showsRelatives = (type: string | undefined): boolean => type === "ELEVE";
