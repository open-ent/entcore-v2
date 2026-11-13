import { Layout } from "@open-ent/react";
import { Outlet } from "react-router-dom";
import { WorkspaceStyle } from "../components/WorkspaceStyle";

export const Root = () => {
  return (
    <Layout>
      <WorkspaceStyle />
      <Outlet />
    </Layout>
  );
};
