import { RouteObject, createBrowserRouter } from "react-router-dom";
import { Home } from "./pages/Home";
import { Root } from "./pages/Root";

export const routes: RouteObject[] = [
  {
    path: "/",
    element: <Root />,
    children: [
      { index: true, element: <Home /> },
      { path: "*", element: <Home /> },
    ],
  },
];

export const createRouter = () => createBrowserRouter(routes, { basename: "/workspace" });
