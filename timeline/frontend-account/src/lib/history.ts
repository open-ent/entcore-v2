export interface Notification {
  _id: string;
  type: string;
  "event-type"?: string;
  sender?: string | null;
  message: string;
  date: { $date: number | string };
  recipients?: unknown;
}

export interface Registered {
  type: string;
  "app-name"?: string;
}

/**
 * Types proposés en filtre : ceux pour lesquels des notifications existent et dont l'utilisateur possède l'application
 * (même règle que l'ancienne interface).
 */
export const accessibleTypes = (
  types: string[],
  registered: Registered[],
  apps: string[],
): string[] => {
  const mine = apps.map((a) => a.toLowerCase());
  return types.filter((type) => {
    const matching = registered.filter((r) => r.type === type);
    if (matching.length === 0) return false;
    return matching.some(
      (r) =>
        mine.includes(r.type.toLowerCase()) ||
        (!!r["app-name"] && mine.includes(r["app-name"].toLowerCase())),
    );
  });
};

/** Paramètres de la requête de notifications : types retenus, page, et « mine » pour l'historique personnel. */
export const notificationsQuery = (
  selected: string[],
  page: number,
): string => {
  const p = new URLSearchParams();
  p.set("page", String(page));
  selected.forEach((t) => p.append("type", t));
  p.set("mine", "1");
  return p.toString();
};

export const toggleType = (selected: string[], type: string): string[] =>
  selected.includes(type)
    ? selected.filter((t) => t !== type)
    : [...selected, type];

/** Tous cochés → on décoche tout ; sinon on coche tout. */
export const toggleAll = (selected: string[], all: string[]): string[] =>
  selected.length === all.length ? [] : [...all];

/** Ajoute les notifications d'une page sans doublon (la pagination peut renvoyer une notification déjà vue). */
export const mergePage = (
  current: Notification[],
  incoming: Notification[],
): Notification[] => {
  const ids = new Set(current.map((n) => n._id));
  return [...current, ...incoming.filter((n) => !ids.has(n._id))];
};

const ALLOWED_TAGS = new Set([
  "A",
  "B",
  "STRONG",
  "I",
  "EM",
  "U",
  "BR",
  "SPAN",
  "P",
  "UL",
  "OL",
  "LI",
]);

/**
 * Réécrit les liens hérités de l'interface AngularJS que les gabarits de notification produisent encore (et que
 * l'historique conserve) quand ils ne mènent plus nulle part :
 *  - messagerie : `/conversation#/read-mail/<id>` (ou `/conversation/conversation#/…`) → `/conversation/inbox/message/<id>` ;
 *  - messagerie, autres routes à ancre : `/conversation#/…` → `/conversation/conversation#/…`, que l'application React redirige.
 * Un lien absolu vers le même ENT devient relatif, pour rester sur l'adresse de la session.
 */
export const fixLegacyHref = (
  href: string,
  origin: string = window.location.origin,
): string => {
  let url = href.trim();
  const abs = /^https?:\/\/[^/]+/i.exec(url);
  if (abs && abs[0].toLowerCase() === origin.toLowerCase())
    url = url.slice(abs[0].length) || "/";
  const readMail = /^\/conversation(?:\/conversation)?\/?#\/read-mail\/([^/?#]+)/.exec(url);
  if (readMail) return `/conversation/inbox/message/${readMail[1]}`;
  if (/^\/conversation\/?#/.test(url))
    return url.replace(/^\/conversation\/?#/, "/conversation/conversation#");
  return url;
};

/** Nettoie le message HTML d'une notification : balises de mise en forme et liens http(s)/relatifs seulement. */
export const sanitizeMessage = (html: string): string => {
  const doc = new DOMParser().parseFromString(
    `<body>${html}</body>`,
    "text/html",
  );
  const walk = (node: Element) => {
    Array.from(node.children).forEach((child) => {
      if (!ALLOWED_TAGS.has(child.tagName)) {
        child.replaceWith(...Array.from(child.childNodes));
        return;
      }
      Array.from(child.attributes).forEach((attr) => {
        const keep =
          child.tagName === "A" &&
          (attr.name === "href" || attr.name === "target");
        if (!keep) child.removeAttribute(attr.name);
      });
      if (child.tagName === "A") {
        const href = child.getAttribute("href") ?? "";
        if (!/^(https?:\/\/|\/|#|mailto:)/i.test(href))
          child.removeAttribute("href");
        else child.setAttribute("href", fixLegacyHref(href));
        child.setAttribute("rel", "noopener noreferrer");
      }
      walk(child);
    });
  };
  walk(doc.body);
  return doc.body.innerHTML;
};
