import { AreaChart } from "@mantine/charts";
import { Alert, Title, Text } from "@mantine/core";
import { IconAlertCircle } from "@tabler/icons-react";
import { type ChartData } from "../types";

export function LatencyChart({
  chartData,
  title,
  loading,
  error,
}: {
  chartData: ChartData[];
  title: string;
  loading?: boolean;
  error?: string;
}) {
  if (error) {
    return (
      <Alert icon={<IconAlertCircle size="1rem" />} title="Error" color="red">
        {error}
      </Alert>
    );
  }

  if (loading) {
    return <Text>Loading chart data...</Text>;
  }

  if (!chartData || chartData.length === 0) {
    return (
      <Alert
        icon={<IconAlertCircle size="1rem" />}
        title="No Data"
        color="blue"
      >
        No data available for the selected date range
      </Alert>
    );
  }

  return (
    <div>
      <Title order={4} mb="md">
        {title}
      </Title>
      <AreaChart
        h={400}
        data={chartData}
        dataKey="timestamp"
        series={[
          { name: "p99", color: "red.6" },
          { name: "p95", color: "orange.6" },
          { name: "p90", color: "yellow.6" },
          { name: "p80", color: "green.6" },
          { name: "p50", color: "blue.6" },
        ]}
        curveType="bump"
        withLegend
        unit={"ms"}
        legendProps={{ verticalAlign: "top", height: 50 }}
        gridAxis="xy"
        withDots={false}
        referenceLines={[
          {
            y: chartData.reduce((max, item) => Math.max(max, item.p99), 0),
            color: "red.3",
            label: "Max p99",
          },
        ]}
      />
    </div>
  );
}
