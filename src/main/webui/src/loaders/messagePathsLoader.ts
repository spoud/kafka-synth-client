// Shared loader for message paths data
// Used by both the overview page and flow visualization

export async function loadMessagePaths() {
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
}
