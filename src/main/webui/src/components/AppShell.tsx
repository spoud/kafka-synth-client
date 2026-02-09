import {
  AppShell as MantineAppShell,
  Group,
  Title,
  ActionIcon,
  useMantineColorScheme,
  rem,
  Box,
  Stack,
} from "@mantine/core";
import { IconSun, IconMoon } from "@tabler/icons-react";
import { Link } from "react-router";

export function AppShell({ children }: { children: any }) {
  const { colorScheme, toggleColorScheme } = useMantineColorScheme();

  return (
    <MantineAppShell header={{ height: 60 }} padding="md">
      <MantineAppShell.Header
        display={"flex"}
        dir={"column"}
        style={{ justifyContent: "center" }}
        px={"xl"}
      >
        <Group
          h="100%"
          justify="space-between"
          style={{ alignSelf: "stretch", flexGrow: 1, maxWidth: rem(1200) }}
        >
          <Link
            to={"/"}
            style={{
              textDecoration: "none",
              color: colorScheme === "dark" ? "white" : "black",
            }}
          >
            <Title order={3}>Kafka Synth Client</Title>
          </Link>
          <ActionIcon
            variant="default"
            onClick={() => toggleColorScheme()}
            size="lg"
            aria-label="Toggle color scheme"
          >
            {colorScheme === "dark" ? (
              <IconSun size="1rem" />
            ) : (
              <IconMoon size="1rem" />
            )}
          </ActionIcon>
        </Group>
      </MantineAppShell.Header>

      <MantineAppShell.Main
        display={"flex"}
        dir={"column"}
        style={{ justifyContent: "center" }}
        px={"xl"}
      >
        <Stack
          style={{ alignSelf: "stretch", flexGrow: 1, maxWidth: rem(1200) }}
        >
          {children}
        </Stack>
      </MantineAppShell.Main>
    </MantineAppShell>
  );
}
