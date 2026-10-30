import { useQuery } from "@tanstack/react-query";
import { accountService } from "./account.service";

export const useAccount = () =>
  useQuery({
    queryKey: ["account"],
    queryFn: async () => {
      const [person, userId] = await Promise.all([accountService.person(), accountService.userId()]);
      const infos = await accountService.infos(userId);
      return { person, infos, userId };
    },
  });
