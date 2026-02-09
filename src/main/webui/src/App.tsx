import { createBrowserRouter } from "react-router";
import { RouterProvider } from "react-router/dom";
import './App.css'

// Import styles of packages that you've installed.
// All packages except `@mantine/hooks` require styles imports
import '@mantine/core/styles.css';
import '@mantine/charts/styles.css';

import { MantineProvider } from '@mantine/core';

let router = createBrowserRouter([
    {
        path: "/",
        element: <div>TODO</div>
    },
    {
        path: "/e2e-latencies/:fromRack/:viaRack/:toRack",
        element: <div>TODO E2E Dashboard</div>,
    },
    {
        path: "/ack-latencies/:fromRack/:brokerRack",
        element: <div>TODO Ack Dashboard</div>,
    }
])

function App() {
  return (
      <MantineProvider>
          <RouterProvider router={router} />
      </MantineProvider>
  );
}

export default App
