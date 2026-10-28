import { Navigate, RouteObject, createHashRouter } from "react-router-dom";
import { Browser } from "./pages/Browser";
import { Root } from "./pages/Root";
import { Viewer } from "./pages/Viewer";

export const routes: RouteObject[] = [
  {
    path: "/",
    element: <Root />,
    children: [
      { index: true, element: <Navigate to="/owner" replace /> },
      { path: "view/:id", element: <Viewer /> },
      { path: ":tree", element: <Browser /> },
      { path: ":tree/:folderId", element: <Browser /> },
    ],
  },
  { path: "*", element: <Navigate to="/owner" replace /> },
];

// Routage par « # » comme l'interface AngularJS : la vue n'est servie que sous /workspace/workspace, un rechargement ou un lien direct ne doit pas viser un autre chemin serveur.
export const createRouter = () => createHashRouter(routes);
