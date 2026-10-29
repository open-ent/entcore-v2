import { Layout } from "@open-ent/react";
import { useState } from "react";
import { Outlet } from "react-router-dom";
import { UiSwitch } from "../components/UiSwitch";
import { UiSwitchBanner } from "../components/UiSwitchBanner";

export const Root = () => {
  // Le bouton de bascule laisse la place au bandeau de retour d'expérience tant qu'il est affiché.
  const [banner, setBanner] = useState(false);
  return (
    <Layout>
      <Outlet />
      <UiSwitchBanner onVisibleChange={setBanner} />
      {!banner && <UiSwitch />}
    </Layout>
  );
};
