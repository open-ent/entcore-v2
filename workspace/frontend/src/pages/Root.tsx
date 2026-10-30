import { Layout } from "@open-ent/react";
import { Outlet } from "react-router-dom";
import { ResponsiveStyle } from "../components/ResponsiveStyle";
import { UiSwitch } from "../components/UiSwitch";
import { UiSwitchBanner } from "../components/UiSwitchBanner";

export const Root = () => {
  return (
    <Layout>
      <ResponsiveStyle />
      <Outlet />
      <UiSwitchBanner />
      {/* toujours affiché ; posé au-dessus du bandeau de retour d'expérience tant qu'il est visible */}
      <UiSwitch />
    </Layout>
  );
};
