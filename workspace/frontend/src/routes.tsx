import { Navigate, RouteObject, createBrowserRouter } from "react-router-dom";
import { Browser } from "./pages/Browser";
import { Root } from "./pages/Root";

export const routes: RouteObject[] = [
  {
    path: "/workspace",
    element: <Root />,
    children: [
      { index: true, element: <Navigate to="owner" replace /> },
      { path: ":tree", element: <Browser /> },
      { path: ":tree/:folderId", element: <Browser /> },
    ],
  },
  { path: "*", element: <Navigate to="/workspace/owner" replace /> },
];

// La vue est servie sous /workspace/workspace : le routeur a pour base la racine du site.
export const createRouter = () => createBrowserRouter(routes);
