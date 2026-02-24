// Create a context for user data
import { createContext, type LoaderFunctionArgs } from "react-router";
import { withBaseURI } from "../utils/baseUtil.ts";

export const attachListenersToContext = async ({
  context,
}: LoaderFunctionArgs) => {
  const listeners = await fetch(withBaseURI("/history/other-racks"));
  let ctx: RackUrlLoaderData;
  if (!listeners.ok) {
    ctx = {
      rackUrls: {},
      error: `Failed to fetch synth-client endpoints: server responded with status ${listeners.statusText}`,
    };
  } else {
    try {
      ctx = {
        rackUrls: (await listeners.json()) as Record<string, string>,
        lastFetched: Date.now(),
      };
    } catch (e) {
      ctx = {
        rackUrls: {},
        error: `Failed to parse rack URLs: ${e}`,
      };
    }
  }
  context.set(rackUrlContext, ctx);
};

export type RackUrlLoaderData = {
  rackUrls: Record<string, string>;
  error?: string;
  lastFetched?: number;
};

export const rackUrlContext = createContext<RackUrlLoaderData>({
  rackUrls: {},
  error: "Rack URLs not loaded yet.",
});
