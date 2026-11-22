import { screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Stats as StatsData } from "@muninn/api";
import { renderPage } from "../test-utils";
import { Stats } from "./Stats";

function months(counts: Record<string, [number, number]> = {}) {
  const result = [];
  for (let i = 0; i < 12; i++) {
    const m = `${i < 1 ? 2025 : 2026}-${String(((i + 11) % 12) + 1).padStart(2, "0")}`;
    const [count, durationMs] = counts[m] ?? [0, 0];
    result.push({ month: m, count, durationMs });
  }
  return result;
}

const stats = (over: Partial<StatsData> = {}): StatsData => ({
  totalRecordings: 3,
  recordingsWithDuration: 3,
  totalDurationMs: 5_400_000,
  averageDurationMs: 1_800_000,
  thisMonthRecordings: 2,
  byMonth: months({ "2026-11": [2, 3_600_000], "2026-10": [1, 1_800_000] }),
  ...over,
});

describe("Stats", () => {
  it("showsTheTilesWithFormattedNumbers", async () => {
    renderPage(<Stats />, { getStats: vi.fn(async () => stats()) });

    const recordings = (await screen.findAllByText("Recordings"))[0].closest("div")!; // the tile comes first
    expect(within(recordings).getByText("3")).toBeInTheDocument();
    expect(within(screen.getByText("Hours recorded").closest("div")!).getByText("1.5")).toBeInTheDocument();
    expect(within(screen.getByText("Average length").closest("div")!).getByText("30:00")).toBeInTheDocument();
    expect(within(screen.getByText("This month").closest("div")!).getByText("2")).toBeInTheDocument();
  });

  it("showsOneBarAndOneTableRowPerMonth", async () => {
    renderPage(<Stats />, { getStats: vi.fn(async () => stats()) });

    expect(await screen.findAllByTestId("bar")).toHaveLength(12);
    const rows = within(screen.getByRole("table")).getAllByRole("row");
    expect(rows).toHaveLength(13); // header + 12 months
    expect(within(rows[12]).getByText("2026-11")).toBeInTheDocument();
    expect(within(rows[12]).getByText("1.0")).toBeInTheDocument(); // hours that month
  });

  it("explainsRecordingsWithoutALength", async () => {
    renderPage(<Stats />, { getStats: vi.fn(async () => stats({ totalRecordings: 5, recordingsWithDuration: 3 })) });
    expect(await screen.findByText(/2 recordings have no length and are not counted in hours or the average/i)).toBeInTheDocument();
  });

  it("showsAnEmptyStateWithoutRecordings", async () => {
    renderPage(<Stats />, {
      getStats: vi.fn(async () =>
        stats({ totalRecordings: 0, recordingsWithDuration: 0, totalDurationMs: 0, averageDurationMs: null, thisMonthRecordings: 0, byMonth: months() }),
      ),
    });
    expect(await screen.findByText(/no recordings yet/i)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("asksForTheBrowsersTimeZone", async () => {
    const getStats = vi.fn(async () => stats());
    renderPage(<Stats />, { getStats });
    await screen.findAllByText("Recordings");
    expect(getStats).toHaveBeenCalledWith(Intl.DateTimeFormat().resolvedOptions().timeZone);
  });
});
