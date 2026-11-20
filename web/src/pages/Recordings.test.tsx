import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { detail, recording, renderPage } from "../test-utils";
import { RecordingDetail } from "./RecordingDetail";
import { Recordings } from "./Recordings";

describe("Recordings", () => {
  it("listShowsStatusBadgePerRecording", async () => {
    const listRecordings = vi.fn(async () => [
      recording({ id: "a", title: "Done one", processingStatus: "COMPLETED" }),
      recording({ id: "b", title: "Waiting one", processingStatus: "PENDING" }),
      recording({ id: "c", title: "Broken one", processingStatus: "FAILED", lastError: "No AI provider configured" }),
    ]);
    renderPage(<Recordings />, { listRecordings });

    const rows = await screen.findAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByText("Ready")).toBeInTheDocument();
    expect(within(rows[1]).getByText("Waiting")).toBeInTheDocument();
    expect(within(rows[2]).getByText("Failed")).toBeInTheDocument();
    expect(within(rows[2]).getByText("No AI provider configured")).toBeInTheDocument();
  });

  it("deleteConfirmRemovesRow", async () => {
    let rows = [recording({ id: "a", title: "Keep me" }), recording({ id: "b", title: "Delete me" })];
    const listRecordings = vi.fn(async () => rows);
    const deleteRecording = vi.fn(async (id: string) => {
      rows = rows.filter((r) => r.id !== id);
    });
    renderPage(<Recordings />, { listRecordings, deleteRecording });

    const row = (await screen.findByText("Delete me")).closest("li")!;
    await userEvent.click(within(row).getByRole("button", { name: "Delete" }));
    expect(deleteRecording).not.toHaveBeenCalled(); // needs confirmation first
    await userEvent.click(within(row).getByRole("button", { name: "Confirm delete" }));

    expect(deleteRecording).toHaveBeenCalledWith("b");
    await vi.waitFor(() => expect(screen.queryByText("Delete me")).not.toBeInTheDocument());
    expect(screen.getByText("Keep me")).toBeInTheDocument();
  });

  it("showsEmptyState", async () => {
    renderPage(<Recordings />, { listRecordings: vi.fn(async () => []) });
    expect(await screen.findByText(/no recordings yet/i)).toBeInTheDocument();
  });
});

describe("RecordingDetail", () => {
  it("detailShowsTranscriptSegmentsAndSummary", async () => {
    const getRecording = vi.fn(async () =>
      detail({
        title: "Planning",
        transcript: {
          text: "Hello team. We ship on Friday.",
          segments: [
            { start: 0, end: 4.2, text: "Hello team." },
            { start: 65, end: 70, text: "We ship on Friday." },
          ],
        },
        summary: { text: "The team plans a Friday release.", actionItems: ["Send release notes", "Book QA"] },
      }),
    );
    renderPage(
      <Routes>
        <Route path="/recordings/:id" element={<RecordingDetail />} />
      </Routes>,
      { getRecording },
      { route: "/recordings/r1" },
    );

    expect(await screen.findByText("Planning")).toBeInTheDocument();
    expect(screen.getByText("The team plans a Friday release.")).toBeInTheDocument();
    expect(screen.getByText("Send release notes")).toBeInTheDocument();
    expect(screen.getByText("Book QA")).toBeInTheDocument();
    expect(screen.getByText("Hello team.")).toBeInTheDocument();
    expect(screen.getByText("We ship on Friday.")).toBeInTheDocument();
    expect(screen.getByText("0:00")).toBeInTheDocument();
    expect(screen.getByText("1:05")).toBeInTheDocument();
  });

  it("showsProgressWhileProcessing", async () => {
    const getRecording = vi.fn(async () => detail({ processingStatus: "TRANSCRIBING" }));
    renderPage(
      <Routes>
        <Route path="/recordings/:id" element={<RecordingDetail />} />
      </Routes>,
      { getRecording },
      { route: "/recordings/r1" },
    );
    expect(await screen.findByText("Transcribing")).toBeInTheDocument();
    expect(screen.getByText(/still being processed/i)).toBeInTheDocument();
  });
});
