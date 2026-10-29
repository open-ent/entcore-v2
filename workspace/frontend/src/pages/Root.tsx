import { Layout } from "@open-ent/react";
import { useState } from "react";
import { Outlet } from "react-router-dom";
import { ResponsiveStyle } from "../components/ResponsiveStyle";
import { UiSwitch } from "../components/UiSwitch";
import { UiSwitchBanner } from "../components/UiSwitchBanner";

export const Root = () => {
  // Le bouton de bascule laisse la place au bandeau de retour d'expérience tant qu'il est affiché.
  const [banner, setBanner] = useState(false);
  return (
    <Layout>
      <ResponsiveStyle />
      <Outlet />
      <UiSwitchBanner onVisibleChange={setBanner} />
      {!banner && <UiSwitch />}
    </Layout>
  );
};
