import { useLoaderData, useNavigation, useRevalidator, useParams } from "react-router";
import { useInterval } from "@mantine/hooks";
import {Stack, Title, Text, Group, Button} from "@mantine/core";
import { type LatencySummary } from "../types";
import { transformLatencyData } from "../utils/dataTransform";
import { LatencyChart } from "./LatencyChart";
import { DateRangeSelector } from "./DateRangeSelector";
import {useEffect} from "react";
import {IconReload} from "@tabler/icons-react";

interface LatencyDashboardProps {
  type: 'e2e' | 'ack';
  title: string;
}

export function LatencyDashboard({ type, title }: LatencyDashboardProps) {
  const { latencyData, error } = useLoaderData() as {
    latencyData: LatencySummary;
    error?: string;
  };
  const params = useParams();
  const navigation = useNavigation();
  const { revalidate, state } = useRevalidator();

  const chartData = transformLatencyData(latencyData);
  const isLoading = navigation.state === "loading";
  const isRevalidating = state === "loading";

  // Auto-refresh every 15 seconds
  const interval = useInterval(
    async () => {
      if (!isLoading) {
        await revalidate();
      }
    },
    15000,
    { autoInvoke: false },
  );
  useEffect(() => {
      interval.start();
      return () => interval.stop();
  }, [])

  // Build descriptive subtitle based on route parameters
  let subtitle = "";
  if (type === 'e2e' && params.fromRack && params.viaRack && params.toRack) {
    subtitle = `E2E Latency between client rack ${params.fromRack}, via broker rack ${params.viaRack}, to client rack ${params.toRack}`;
  } else if (type === 'ack' && params.fromRack && params.brokerRack) {
    subtitle = `Ack Latency between client rack ${params.fromRack} and broker rack ${params.brokerRack}`;
  }

  return (
    <Stack gap="lg">
        <Group justify={"space-between"}>
            <Title order={2}>{title} Dashboard</Title>
            <Button variant={"light"} leftSection={<IconReload />} loading={isLoading || isRevalidating} disabled={isLoading || isRevalidating} onClick={revalidate}>Refresh</Button>
        </Group>

      {subtitle && (
        <Text size="lg" fw={500} c="dimmed">
          {subtitle}
        </Text>
      )}
      
      <DateRangeSelector />
      
      <LatencyChart
        chartData={chartData}
        title={`Percentiles`}
        loading={isLoading}
        error={error}
      />
      
      {chartData.length > 0 && (
        <Text size="xs" c="dimmed">
          Last updated: {new Date().toLocaleString()}
        </Text>
      )}
    </Stack>
  );
}