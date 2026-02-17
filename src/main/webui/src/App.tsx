import { createBrowserRouter } from "react-router";
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

let router = createBrowserRouter([
  {
    path: withBaseURI("/"),
    element: (
      <AppShell>
        <MessagePathsDashboard />
      </AppShell>
    ),
    loader: loadMessagePaths,
  },
  {
    path: withBaseURI("/e2e-latencies/:fromRack/:viaRack/:toRack"),
    element: (
      <AppShell>
        <E2ELatencyDashboard />
      </AppShell>
    ),
    loader: ({ params, request }) => loadE2ELatencies({ params, request }),
  },
  {
    path: withBaseURI("/ack-latencies/:fromRack/:brokerRack"),
    element: (
      <AppShell>
        <AckLatencyDashboard />
      </AppShell>
    ),
    loader: ({ params, request }) => loadAckLatencies({ params, request }),
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
