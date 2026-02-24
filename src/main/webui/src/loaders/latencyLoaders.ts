// Loaders for latency dashboard routes

import { joinUrl, withBaseURI } from "../utils/baseUtil.ts";
import type { LoaderFunctionArgs } from "react-router";
import { rackUrlContext, type RackUrlLoaderData } from "./rackUrls.ts";

interface LatencyLoaderParams {
  params: {
    fromRack?: string;
    viaRack?: string;
    toRack?: string;
    brokerRack?: string;
  };
}

/**
 * Generic latency loader that handles the common logic for both E2E and Ack latencies
 * @param params Route parameters
 * @param request Request object
 * @param context React Router context object
 * @param endpointType 'e2e' or 'ack' to determine which API endpoint to call
 * @returns Promise with latency data or error
 */
async function loadLatencies({
  params,
  context,
  request,
  endpointType,
}: LatencyLoaderParams & { endpointType: "e2e" | "ack" } & LoaderFunctionArgs) {
  try {
    const url = new URL(request.url);
    const intervalStart = url.searchParams.get("interval_start");
    const intervalEnd = url.searchParams.get("interval_end");

    const rackUrls: RackUrlLoaderData = context.get(rackUrlContext);
    const fetchUrl = rackUrls.rackUrls[params.toRack ?? params.fromRack!] ?? "";

    // Build query string for date range filtering
    let query = "";
    if (intervalStart || intervalEnd) {
      query =
        "?" +
        new URLSearchParams({
          interval_start: intervalStart || "",
          interval_end: intervalEnd || "",
        }).toString();
    }

    // Determine the API endpoint based on type
    let apiEndpoint;
    if (endpointType === "e2e") {
      apiEndpoint = `/history/e2e-latencies/${params.fromRack}/${params.viaRack}/${params.toRack}${query}`;
    } else {
      apiEndpoint = `/history/ack-latencies/${params.fromRack}/${params.brokerRack}${query}`;
    }

    const response = await fetch(withBaseURI(joinUrl(fetchUrl, apiEndpoint)));
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    const data = await response.json();
    return { latencyData: data };
  } catch (error) {
    const errorType = endpointType === "e2e" ? "E2E" : "Ack";
    console.error(`Failed to fetch ${errorType} latencies:`, error);
    return {
      latencyData: { timestamps: [], percentiles: {} },
      error: error instanceof Error ? error.message : "Unknown error",
    };
  }
}

// Specific loader functions that use the generic loader
export async function loadE2ELatencies(
  params: LatencyLoaderParams & LoaderFunctionArgs,
) {
  return loadLatencies({
    ...params,
    endpointType: "e2e",
    context: params.context,
  });
}

export async function loadAckLatencies(
  params: LatencyLoaderParams & LoaderFunctionArgs,
) {
  return loadLatencies({
    ...params,
    endpointType: "ack",
    context: params.context,
  });
}
