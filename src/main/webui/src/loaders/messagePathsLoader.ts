// Shared loader for message paths data
import { withBaseURI } from "../utils/baseUtil.ts";
import type { MessagePath } from "../types.ts";
import { rackUrlContext } from "./rackUrls.ts";
import type { LoaderFunctionArgs } from "react-router";
import { joinUrl } from "../utils/baseUtil.ts";

type MessagePathsLoaderData = {
  fetchFailures: string[];
  messagePaths: MessagePath[];
  lastUpdated: string;
};

const loadMessagePathsFromEndpoint = async (
  rackUrl: string,
  suffix: string,
): Promise<MessagePathsLoaderData> => {
  try {
    const response = await fetch(withBaseURI(rackUrl));
    if (!response.ok) {
      const failure = `Failed to fetch data from ${rackUrl}: server responded with status ${response.statusText}`;
      return {
        messagePaths: [],
        fetchFailures: [failure],
        lastUpdated: new Date().toISOString(),
      };
    }
    const data = ((await response.json()) as MessagePath[]).map((mp) => {
      mp.queryUrl = rackUrl.replace(suffix, "");
      return mp;
    });
    return {
      messagePaths: data,
      fetchFailures: [],
      lastUpdated: new Date().toISOString(),
    };
  } catch (error) {
    const failure = `Failed to fetch data from ${rackUrl}: ${error}`;
    return {
      messagePaths: [],
      fetchFailures: [failure],
      lastUpdated: new Date().toISOString(),
    };
  }
};

function pathToSortKey(p: MessagePath): string {
  return `${p.fromRack}->${p.viaBrokerRack}->${p.toRack}`;
}

export async function loadMessagePaths({
  context,
}: LoaderFunctionArgs): Promise<MessagePathsLoaderData> {
  if (context.get(rackUrlContext)?.error) {
    return {
      messagePaths: [],
      fetchFailures: [context.get(rackUrlContext).error],
      lastUpdated: new Date().toISOString(),
    };
  }
  const listenersData = context.get(rackUrlContext).rackUrls;
  const suffix = "/history/message-paths";
  const rackUrls = [
    ...new Set(
      Object.values(listenersData).map((url) => joinUrl(url as string, suffix)),
    ),
  ];
  rackUrls.push(suffix);
  const messagePaths: MessagePath[] = [];
  const fetchFailures: string[] = [];
  (
    await Promise.all(
      rackUrls.map((url) => loadMessagePathsFromEndpoint(url, suffix)),
    )
  ).forEach((loaderData) => {
    messagePaths.push(...loaderData.messagePaths);
    fetchFailures.push(...loaderData.fetchFailures);
  });

  return {
    fetchFailures,
    messagePaths: messagePaths.sort((a, b) =>
      pathToSortKey(a).localeCompare(pathToSortKey(b)),
    ),
    lastUpdated: new Date().toISOString(),
  };
}
