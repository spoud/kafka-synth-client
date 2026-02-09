import { test, expect } from '@playwright/test';

test('has title', async ({ page }) => {
  await page.goto('/');

  // Expect a title "to contain" a substring.
  await expect(page).toHaveTitle(/synth/);

  // expect message paths section
  await expect(page.getByText("E2E Latencies")).toBeVisible();

  const e2eCard = page.getByTestId("e2e-path-card");
  await expect(e2eCard).toBeVisible();
  await expect(e2eCard.locator("p")).toHaveText("Latest p99");
});
