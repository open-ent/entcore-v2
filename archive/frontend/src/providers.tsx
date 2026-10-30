import { ERROR_CODE } from "@open-ent/client";
import { EdificeClientProvider } from "@open-ent/react";
import { QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactNode } from "react";

export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error) => {
      if (typeof error === "string" && error === ERROR_CODE.NOT_LOGGED_IN) {
        window.location.replace("/auth/login");
      }
    },
  }),
  defaultOptions: {
    queries: { retry: false, refetchOnWindowFocus: false, staleTime: 1000 * 30 },
  },
});

export const Providers = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>
    <EdificeClientProvider params={{ app: "archive" }}>{children}</EdificeClientProvider>
  </QueryClientProvider>
);
