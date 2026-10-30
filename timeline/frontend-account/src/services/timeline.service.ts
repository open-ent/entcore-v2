import { odeServices } from "@open-ent/client";
import { Default, Frequency } from "../lib/notifs";

export type Preference = {
  config?: Record<string, { defaultFrequency?: Frequency }>;
} | null;

import type { Notification, Registered } from "../lib/history";

export const timelineService = {
  types: () => odeServices.http().get<string[]>("/timeline/types"),
  registered: () =>
    odeServices.http().get<Registered[]>("/timeline/registeredNotifications"),
  notifications: (query: string) =>
    odeServices
      .http()
      .get<{ results: Notification[] }>(`/timeline/lastNotifications?${query}`),
  deleteNotification: (id: string) =>
    odeServices.http().delete<unknown>(`/timeline/${id}`),
  defaults: () =>
    odeServices.http().get<Default[]>("/timeline/notifications-defaults"),
  /** La préférence est rangée en chaîne JSON côté serveur. */
  preference: async (): Promise<Preference> => {
    const res = await odeServices
      .http()
      .get<{ preference: string | null }>("/userbook/preference/timeline");
    try {
      return res.preference ? JSON.parse(res.preference) : {};
    } catch {
      return {};
    }
  },
  savePreference: (body: Preference) =>
    odeServices.http().put<unknown>("/userbook/preference/timeline", body),
  person: async () =>
    (
      await odeServices
        .http()
        .get<{ result: Array<{ id: string; email?: string | null }> }>(
          "/userbook/api/person?force=true",
        )
    ).result?.[0],
  quietHours: async () =>
    (
      await odeServices
        .http()
        .get<{ quietHours?: { enabled?: boolean; managedBy?: string } }>(
          "/userbook/api/preferences",
        )
    ).quietHours,
  /** Libellés des notifications (clés `<application>.<type>`), chargés à la demande. */
  notificationLabels: () =>
    odeServices
      .http()
      .get<Record<string, string>>("/timeline/i18nNotifications"),
};
