import { expect, test, type Page } from "@playwright/test";

const messagePaths = [
  {
    fromRack: "dc1",
    viaBrokerRack: "broker-a",
    toRack: "dc2",
    latestP99latency: 42,
    latestP99AckLatency: 12,
  },
];

const latencySummary = {
  timestamps: [
    Date.parse("2026-03-20T14:00:00Z"),
    Date.parse("2026-03-20T14:05:00Z"),
  ],
  percentiles: {
    p50: [8, 9],
    p80: [12, 13],
    p90: [18, 19],
    p95: [24, 25],
    p99: [31, 32],
  },
};

async function mockApi(page: Page) {
  await page.route("**/history/other-racks**", async (route) => {
    await route.fulfill({ json: {} });
  });

  await page.route("**/history/message-paths**", async (route) => {
    await route.fulfill({ json: messagePaths });
  });

  await page.route("**/history/e2e-latencies/**", async (route) => {
    await route.fulfill({ json: latencySummary });
  });

  await page.route("**/history/ack-latencies/**", async (route) => {
    await route.fulfill({ json: latencySummary });
  });
}

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

test("loads the dashboard smoke view", async ({ page }) => {
  await page.goto("/");

  await expect(
    page.getByRole("heading", { name: "Kafka Synth Client" }),
  ).toBeVisible();
  await expect(page.getByText("End-to-End Latencies")).toBeVisible();
  await expect(page.getByText("Ack Latencies")).toBeVisible();
  await expect(page.getByText("latest p99: 42 ms")).toBeVisible();
  await expect(page.getByText("latest p99: 12 ms")).toBeVisible();
  await expect(page.getByText(/Last updated at:/)).toBeVisible();
});

test("navigates to the end-to-end latency detail page", async ({ page }) => {
  await page.goto("/");
  await page
    .getByRole("link", {
      name: "Open end-to-end latency for client rack dc1 via broker rack broker-a to client rack dc2",
    })
    .click();

  await expect(page).toHaveURL("/e2e-latencies/dc1/broker-a/dc2");
  await expect(
    page.getByRole("heading", { name: "E2E Latency Dashboard" }),
  ).toBeVisible();
  await expect(
    page.getByText(
      "E2E Latency between client rack dc1, via broker rack broker-a, to client rack dc2",
    ),
  ).toBeVisible();
  await expect(page.getByText("Percentiles")).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh" })).toBeVisible();
  await expect(page.getByText(/Last updated:/)).toBeVisible();
});

test("renders the ack latency detail page directly", async ({ page }) => {
  await page.goto("/ack-latencies/dc1/broker-a");

  await expect(page).toHaveURL("/ack-latencies/dc1/broker-a");
  await expect(
    page.getByRole("heading", { name: "Ack Latency Dashboard" }),
  ).toBeVisible();
  await expect(
    page.getByText(
      "Ack Latency between client rack dc1 and broker rack broker-a",
    ),
  ).toBeVisible();
  await expect(page.getByText("Percentiles")).toBeVisible();
  await expect(page.getByText("Log Scale")).toBeVisible();
});
