import { Layout } from "@open-ent/react";
import { Outlet } from "react-router-dom";
import { UiSwitch } from "../components/UiSwitch";

export const Root = () => (
  <Layout>
    <Outlet />
    <UiSwitch />
  </Layout>
);
