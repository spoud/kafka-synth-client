import {
  createBrowserRouter,
  isRouteErrorResponse,
  Outlet,
  useRouteError,
} from "react-router";
import { RouterProvider } from "react-router/dom";

// Import styles of packages that you've installed.
// All packages except `@mantine/hooks` require styles imports
import "@mantine/core/styles.css";
import "@mantine/charts/styles.css";
import "@mantine/dates/styles.css";

import { MantineProvider } from "@mantine/core";
import { AppShell } from "./components/AppShell";
import { MessagePathsDashboard } from "./components/MessagePathsDashboard";
import { E2ELatencyDashboard } from "./components/E2ELatencyDashboard";
import { AckLatencyDashboard } from "./components/AckLatencyDashboard";
import { loadMessagePaths } from "./loaders/messagePathsLoader";
import { loadE2ELatencies, loadAckLatencies } from "./loaders/latencyLoaders";
import { withBaseURI } from "./utils/baseUtil.ts";

function RootErrorBoundary() {
  let error = useRouteError();

  if (isRouteErrorResponse(error)) {
    return (
      <>
        <h1>
          {error.status} {error.statusText}
        </h1>
        <p>{error.data}</p>
      </>
    );
  } else if (error instanceof Error) {
    return (
      <div>
        <h1>Error</h1>
        <p>{error.message}</p>
        <p>The stack trace is:</p>
        <pre>{error.stack}</pre>
      </div>
    );
  } else {
    return <h1>Unknown Error</h1>;
  }
}

let router = createBrowserRouter([
  {
    path: withBaseURI("/"),
    ErrorBoundary: RootErrorBoundary,
    element: (
      <AppShell>
        <Outlet />
      </AppShell>
    ),
    loader: loadMessagePaths,
    children: [
      {
        index: true,
        element: <MessagePathsDashboard />,
        loader: loadMessagePaths,
      },
      {
        path: "e2e-latencies/:fromRack/:viaRack/:toRack",
        element: <E2ELatencyDashboard />,
        loader: ({ params, request }) => loadE2ELatencies({ params, request }),
      },
      {
        path: "ack-latencies/:fromRack/:brokerRack",
        element: <AckLatencyDashboard />,
        loader: ({ params, request }) => loadAckLatencies({ params, request }),
      },
    ],
  },
]);

function App() {
  return (
    <MantineProvider>
      <RouterProvider router={router} />
    </MantineProvider>
  );
}

export default App;
