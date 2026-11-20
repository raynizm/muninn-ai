import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router-dom";
import { vi } from "vitest";
import type { ApiClient, Recording, RecordingDetail, UserView } from "@muninn/api";
import { ApiProvider } from "./api";
import { AuthProvider } from "./auth/AuthProvider";

export const user = (role: "ADMIN" | "USER" = "USER"): UserView => ({
  id: "u1",
  email: "ann@example.com",
  displayName: "Ann",
  role,
});

export const recording = (over: Partial<Recording> = {}): Recording => ({
  id: "r1",
  title: "Standup",
  createdAt: "2026-11-20T09:00:00Z",
  updatedAt: "2026-11-20T09:05:00Z",
  durationMs: 65_000,
  mime: "audio/mp4",
  sizeBytes: 1234,
  uploadStatus: "UPLOADED",
  processingStatus: "COMPLETED",
  lastError: null,
  ...over,
});

export const detail = (over: Partial<RecordingDetail> = {}): RecordingDetail => ({
  ...recording(),
  transcript: null,
  summary: null,
  ...over,
});

/** Renders a page with a fake API client, auth state and router. Unlisted client methods are undefined. */
export function renderPage(
  ui: ReactElement,
  client: Partial<ApiClient>,
  { signedInAs = "USER", route = "/" }: { signedInAs?: "ADMIN" | "USER" | null; route?: string } = {},
) {
  const full = {
    me: signedInAs ? vi.fn(async () => user(signedInAs)) : vi.fn().mockRejectedValue(new Error("signed out")),
    ...client,
  } as ApiClient;
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ApiProvider client={full}>
        <AuthProvider>
          <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
        </AuthProvider>
      </ApiProvider>
    </QueryClientProvider>,
  );
}
