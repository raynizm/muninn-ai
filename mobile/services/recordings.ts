import { ApiError, type ApiClient, type ProcessingStatus, type Recording } from "@muninn/api";
import type { LocalRecording, UploadStatus } from "./db";

/** A recording as the user sees it: the phone's copy and the server's copy joined by id. */
export interface MergedRecording {
  id: string;
  title: string | null;
  createdAt: string;
  durationMs: number | null;
  /** `file://` path of the audio on this phone, if it is still there. */
  localPath: string | null;
  uploadStatus: UploadStatus;
  processingStatus: ProcessingStatus | null;
  lastError: string | null;
  onDevice: boolean;
  onServer: boolean;
}

/** Server data wins for title, processing status and errors; the phone supplies what the server lacks. */
export function mergeRecordings(local: LocalRecording[], server: Recording[]): MergedRecording[] {
  const byId = new Map<string, MergedRecording>();

  for (const l of local) {
    byId.set(l.id, {
      id: l.id,
      title: null,
      createdAt: l.createdAt,
      durationMs: l.durationMs,
      localPath: l.filePath,
      uploadStatus: l.uploadStatus,
      processingStatus: l.serverStatus, // last status the server reported, for offline viewing
      lastError: l.lastError,
      onDevice: true,
      onServer: false,
    });
  }

  for (const s of server) {
    const existing = byId.get(s.id);
    byId.set(s.id, {
      id: s.id,
      title: s.title,
      createdAt: s.createdAt,
      durationMs: s.durationMs ?? existing?.durationMs ?? null,
      localPath: existing?.localPath ?? null,
      uploadStatus: "UPLOADED", // the server has it, whatever the local row still says
      processingStatus: s.processingStatus,
      lastError: s.lastError ?? null,
      onDevice: existing?.onDevice ?? false,
      onServer: true,
    });
  }

  return [...byId.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

const PROCESSING_LABELS: Record<ProcessingStatus, string> = {
  PENDING: "Waiting",
  TRANSCRIBING: "Transcribing",
  SUMMARIZING: "Summarizing",
  COMPLETED: "Ready",
  FAILED: "Processing failed",
};

/** Short label for the one thing the user most needs to know about this recording. */
export function badgeFor(item: Pick<MergedRecording, "uploadStatus" | "processingStatus">): string {
  switch (item.uploadStatus) {
    case "LOCAL_ONLY":
      return "On this phone";
    case "QUEUED":
      return "Queued";
    case "UPLOADING":
      return "Uploading";
    case "FAILED":
      return "Upload failed";
    case "UPLOADED":
      return item.processingStatus ? PROCESSING_LABELS[item.processingStatus] : "Uploaded";
  }
}

/**
 * The server's recordings, kept up to date from `GET /api/recordings?updatedSince=`. That endpoint cannot
 * report deletions, so every Nth successful poll reloads everything. When the server is unreachable the last
 * known list is kept.
 */
export class ServerCache {
  private readonly items = new Map<string, Recording>();
  private cursor: string | undefined;
  private polls = 0;

  constructor(private readonly fullRefreshEvery = 6) {}

  async refresh(client: ApiClient): Promise<{ items: Recording[]; reachable: boolean }> {
    const full = this.polls % this.fullRefreshEvery === 0;
    try {
      const changed = await client.listRecordings(full ? undefined : this.cursor);
      if (full) this.items.clear();
      for (const r of changed) this.items.set(r.id, r);
      this.cursor = undefined;
      for (const r of this.items.values()) if (!this.cursor || r.updatedAt > this.cursor) this.cursor = r.updatedAt;
      this.polls += 1;
      return { items: [...this.items.values()], reachable: true };
    } catch {
      return { items: [...this.items.values()], reachable: false };
    }
  }

  forget(id: string): void {
    this.items.delete(id);
  }
}

export interface DeleteDeps {
  client: Pick<ApiClient, "deleteRecording">;
  store: { delete(id: string): Promise<void> };
  deleteFile(path: string): void;
}

/**
 * Deletes a recording on the server first, then on the phone. If the server cannot be reached nothing is
 * removed, so the user never ends up believing something is gone that is still on the server.
 */
export async function deleteEverywhere(
  item: Pick<MergedRecording, "id" | "onServer" | "localPath">,
  deps: DeleteDeps,
): Promise<void> {
  if (item.onServer) {
    try {
      await deps.client.deleteRecording(item.id);
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 404)) throw e; // already gone is fine
    }
  }
  await deps.store.delete(item.id);
  if (item.localPath) deps.deleteFile(item.localPath);
}
