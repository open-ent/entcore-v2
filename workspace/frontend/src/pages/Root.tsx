import { Layout } from "@open-ent/react";
import { Outlet } from "react-router-dom";

export const Root = () => (
  <Layout>
    <Outlet />
  </Layout>
);
