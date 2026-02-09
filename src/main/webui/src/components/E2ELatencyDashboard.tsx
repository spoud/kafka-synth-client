import { useLoaderData, useNavigation, useRevalidator, useParams } from "react-router";
import { useInterval } from "@mantine/hooks";
import { Stack, Title, Text } from "@mantine/core";
import { type LatencySummary } from "../types";
import { transformLatencyData } from "../utils/dataTransform";
import { LatencyChart } from "./LatencyChart";
import { DateRangeSelector } from "./DateRangeSelector";

export function E2ELatencyDashboard() {
  const { latencyData, error } = useLoaderData() as {
    latencyData: LatencySummary;
    error?: string;
  };
  const { fromRack, viaRack, toRack } = useParams();
  const navigation = useNavigation();

  const chartData = transformLatencyData(latencyData);
  const isLoading = navigation.state === "loading";

  // Auto-refresh every 15 seconds
  const { revalidate } = useRevalidator();
  useInterval(
    async () => {
      if (!isLoading) {
        await revalidate();
      }
    },
    15000,
    { autoInvoke: true },
  );

  const title = `E2E latency between client rack '${fromRack}', via broker rack '${viaRack}', to client rack '${toRack}'`;

  return (
    <Stack gap="lg">
      <Title order={2}>E2E Latency Dashboard</Title>

      <DateRangeSelector />

      <LatencyChart
        chartData={chartData}
        title={title}
        loading={isLoading}
        error={error}
      />

      {chartData.length > 0 && (
        <Text size="sm" c="dimmed">
          Last updated: {new Date().toLocaleString()}
        </Text>
      )}
    </Stack>
  );
}
