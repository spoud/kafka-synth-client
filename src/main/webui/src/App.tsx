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

let router = createBrowserRouter([
  {
    path: "/",
    element: (
      <AppShell>
        <MessagePathsDashboard />
      </AppShell>
    ),
    loader: async () => {
      try {
        const response = await fetch("/history/message-paths");
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = await response.json();
        return {
          messagePaths: data,
          lastUpdated: new Date().toISOString(),
        };
      } catch (error) {
        console.error("Failed to fetch message paths:", error);
        return {
          messagePaths: [],
          error: error instanceof Error ? error.message : "Unknown error",
          lastUpdated: new Date().toISOString(),
        };
      }
    },
  },
  {
    path: "/e2e-latencies/:fromRack/:viaRack/:toRack",
    element: (
      <AppShell>
        <E2ELatencyDashboard />
      </AppShell>
    ),
    loader: async ({ params, request }) => {
      try {
        const url = new URL(request.url);
        const intervalStart = url.searchParams.get("interval_start");
        const intervalEnd = url.searchParams.get("interval_end");

        let query = "";
        if (intervalStart || intervalEnd) {
          query =
            "?" +
            new URLSearchParams({
              interval_start: intervalStart || "",
              interval_end: intervalEnd || "",
            }).toString();
        }

        const response = await fetch(
          `/history/e2e-latencies/${params.fromRack}/${params.viaRack}/${params.toRack}${query}`,
        );
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = await response.json();
        return { latencyData: data };
      } catch (error) {
        console.error("Failed to fetch E2E latencies:", error);
        return {
          latencyData: { timestamps: [], percentiles: {} },
          error: error instanceof Error ? error.message : "Unknown error",
        };
      }
    },
  },
  {
    path: "/ack-latencies/:fromRack/:brokerRack",
    element: (
      <AppShell>
        <AckLatencyDashboard />
      </AppShell>
    ),
    loader: async ({ params, request }) => {
      try {
        const url = new URL(request.url);
        const intervalStart = url.searchParams.get("interval_start");
        const intervalEnd = url.searchParams.get("interval_end");

        let query = "";
        if (intervalStart || intervalEnd) {
          query =
            "?" +
            new URLSearchParams({
              interval_start: intervalStart || "",
              interval_end: intervalEnd || "",
            }).toString();
        }

        const response = await fetch(
          `/history/ack-latencies/${params.fromRack}/${params.brokerRack}${query}`,
        );
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = await response.json();
        return { latencyData: data };
      } catch (error) {
        console.error("Failed to fetch Ack latencies:", error);
        return {
          latencyData: { timestamps: [], percentiles: {} },
          error: error instanceof Error ? error.message : "Unknown error",
        };
      }
    },
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
