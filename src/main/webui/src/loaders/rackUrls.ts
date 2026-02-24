// Create a context for user data
import { createContext } from "react-router";

export type RackUrlLoaderData = {
  rackUrls: Record<string, string>;
  error?: string;
  lastFetched?: number;
};

export const rackUrlContext = createContext<RackUrlLoaderData>({
  rackUrls: {},
  error: "Rack URLs not loaded yet.",
});
