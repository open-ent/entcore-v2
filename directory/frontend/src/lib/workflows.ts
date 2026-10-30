/** Droits (workflows) qui commandent les onglets et les actions de Mon compte. */
export const WF = {
  EXTERNAL_NOTIFICATIONS: "org.entcore.timeline.controllers.TimelineController|mixinConfig",
  HISTORY: "org.entcore.timeline.controllers.TimelineController|historyView",
  SWITCH_THEME: "org.entcore.directory.controllers.UserBookController|userBookSwitchTheme",
  MOTTO_MOOD: "org.entcore.directory.controllers.UserBookController|userBookMottoMood",
  SHARE_BOOKMARKS: "org.entcore.directory.controllers.ShareBookmarkController|allowSharebookmarks",
  ALLOW_LOGIN_UPDATE: "org.entcore.directory.controllers.UserController|allowLoginUpdate",
} as const;

export const workflowsOf = (user: unknown): Set<string> =>
  new Set(((user as { authorizedActions?: Array<{ name: string }> } | undefined)?.authorizedActions ?? []).map((a) => a.name));
