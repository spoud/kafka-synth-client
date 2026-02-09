import { useLoaderData, Link, useRevalidator } from "react-router";
import {
  Card,
  Text,
  Group,
  Title,
  SimpleGrid,
  Loader,
  Alert,
  Stack,
  ActionIcon,
  HoverCard,
} from "@mantine/core";
import { IconAlertCircle, IconHelp } from "@tabler/icons-react";
import { type MessagePath } from "../types";
import { useInterval, useTimeout } from "@mantine/hooks";
import { useEffect, useState } from "react";
import dayjs from "dayjs";

import classes from "./MessagePathsDashboard.module.css";

// Explanation Component - Displays help icon with hoverable explanation
function Explanation({ content }: { content: string }) {
  return (
    <HoverCard width={300} shadow="md" position="top" withArrow>
      <HoverCard.Target>
        <ActionIcon variant="subtle" size="sm">
          <IconHelp />
        </ActionIcon>
      </HoverCard.Target>
      <HoverCard.Dropdown>
        <Text size="sm">{content}</Text>
      </HoverCard.Dropdown>
    </HoverCard>
  );
}

// Message Path Card Component - Reusable for both E2E and Ack paths
function MessagePathCard({
  path,
  to,
  latencyColor = "var(--mantine-color-green-6)",
  showToRack = true,
  hideLatency = false,
}: {
  path: MessagePath;
  to: string;
  hideLatency?: boolean;
  showToRack?: boolean;
  latencyColor?: string;
}) {
  return (
    <Link to={to} style={{ textDecoration: "none" }}>
      <Card withBorder p={"md"} shadow="md" radius={"lg"}>
        <Stack gap={"md"} style={{ fontSize: "var(--mantine-font-size-sm)" }}>
          <Stack>
            <Group justify="space-between">
              <span>From Client Rack:</span>
              <span className={classes.rack}>{path.fromRack}</span>
            </Group>
            <Group justify="space-between">
              <span>Via Broker Rack:</span>
              <span className={classes.rack}>{path.viaBrokerRack}</span>
            </Group>
            {showToRack && (
              <Group justify="space-between">
                <span>To Client Rack:</span>
                <span className={classes.rack}>{path.toRack}</span>
              </Group>
            )}
          </Stack>
          {hideLatency || (
            <Text
              size="xs"
              c="dimmed"
              style={{ alignSelf: "flex-end" }}
              className={classes.rack}
            >
              latest p99:{" "}
              <span style={{ color: latencyColor }}>
                {" "}
                {Math.round(path.latestP99latency)} ms
              </span>
            </Text>
          )}
        </Stack>
      </Card>
    </Link>
  );
}

export function MessagePathsDashboard() {
  const { messagePaths, error, lastUpdated } = useLoaderData() as {
    messagePaths: MessagePath[];
    error?: string;
    lastUpdated?: string;
  };

  const { revalidate } = useRevalidator();
  const [latencyColor, setLatencyColor] = useState<string>(
    "var(--mantine-color-green-6)",
  );

  const { start, clear } = useTimeout(
    async () => {
      setLatencyColor("var(--mantine-color-dimmed)");
    },
    2000,
    { autoInvoke: true },
  );
  useEffect(() => {
    return () => clear();
  }, []);
  useInterval(
    async () => {
      await revalidate();
      setLatencyColor("var(--mantine-color-green-6)");
      start();
    },
    15000,
    { autoInvoke: true },
  );

  if (error) {
    return (
      <Alert icon={<IconAlertCircle size="1rem" />} title="Error" color="red">
        {error}
      </Alert>
    );
  }

  if (!messagePaths) {
    return (
      <Group justify="center" pt="xl">
        <Loader size="xl" />
      </Group>
    );
  }

  return (
    <Stack gap="xl">
      {messagePaths.length > 0 && (
        <div>
          <Title
            order={4}
            mb="sm"
            display={"flex"}
            style={{ gap: "var(--mantine-spacing-xs)", alignItems: "center" }}
          >
            <span>End-to-End Latencies</span>
            <Explanation
              content={
                "The end-to-end latency is measured by producing a message to Kafka and then consuming it again. This metric gives you an idea of how well Kafka is working for all your client applications."
              }
            />
          </Title>
          <SimpleGrid cols={{ base: 1, sm: 2, md: 3, lg: 4 }}>
            {messagePaths.map((path) => (
              <MessagePathCard
                key={`${path.fromRack}-${path.viaBrokerRack}-${path.toRack}`}
                path={path}
                to={`/e2e-latencies/${path.fromRack}/${path.viaBrokerRack}/${path.toRack}`}
                showToRack={true}
                latencyColor={latencyColor}
              />
            ))}
          </SimpleGrid>
        </div>
      )}

      {messagePaths.length > 0 && (
        <div>
          <Title
            order={4}
            mb="sm"
            display={"flex"}
            style={{ gap: "var(--mantine-spacing-xs)", alignItems: "center" }}
          >
            <span>Ack Latencies</span>
            <Explanation
              content={
                "The ack latency is the time between your client making a produce request and receiving an acknowledgement from the broker. High/increasing values could indicate issues with inter-broker communication."
              }
            />
          </Title>
          <SimpleGrid cols={{ base: 1, sm: 2, md: 3, lg: 4 }}>
            {messagePaths.map((path) => (
              <MessagePathCard
                key={`${path.fromRack}-${path.viaBrokerRack}`}
                path={path}
                to={`/ack-latencies/${path.fromRack}/${path.viaBrokerRack}`}
                showToRack={false}
                latencyColor={latencyColor}
                hideLatency={true}
              />
            ))}
          </SimpleGrid>
        </div>
      )}

      {messagePaths.length > 0 && lastUpdated && (
        <Text size="xs" c="dimmed" style={{ textAlign: "left" }}>
          Last updated at: {dayjs(lastUpdated).format("YYYY-MM-DD HH:mm:ss")}
        </Text>
      )}
    </Stack>
  );
}
