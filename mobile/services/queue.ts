import { ApiError, type ApiClient } from "@muninn/api";
import type { LocalRecording, RecordingStore } from "./db";

const BASE_BACKOFF_MS = 5_000;
const MAX_BACKOFF_MS = 15 * 60_000;
/** The server will never accept these as sent, so retrying is pointless. */
const PERMANENT = new Set([400, 404, 409, 413, 415, 422]);

/** Delay before retry number `attempts` (1-based): 5s, 10s, 20s ... capped at 15 minutes. */
export function backoffMs(attempts: number): number {
  return Math.min(BASE_BACKOFF_MS * 2 ** Math.max(0, attempts - 1), MAX_BACKOFF_MS);
}

export interface QueueDeps {
  store: RecordingStore;
  isOnline(): Promise<boolean>;
  /** Epoch milliseconds. */
  now(): number;
  /** The audio file as a request body. */
  openFile(path: string): BodyInit;
  fileExists(path: string): boolean;
}

/**
 * Uploads recordings one at a time. State lives in SQLite, so a kill or restart loses nothing. Audio files
 * are never deleted here: they stay on the phone until the user deletes the recording.
 */
export class UploadQueue {
  private running: Promise<void> | null = null;

  constructor(private readonly deps: QueueDeps) {}

  /** Call once on app start: an UPLOADING row at that point belongs to a process that no longer exists. */
  async recoverOnStart(): Promise<void> {
    await this.deps.store.resetStaleUploading();
  }

  async enqueue(id: string): Promise<void> {
    await this.deps.store.markQueued(id);
  }

  /** Uploads everything that is due. Concurrent calls share one run. */
  drain(client: ApiClient): Promise<void> {
    this.running ??= this.run(client).finally(() => {
      this.running = null;
    });
    return this.running;
  }

  private async run(client: ApiClient): Promise<void> {
    if (!(await this.deps.isOnline())) return;
    for (;;) {
      const rec = await this.deps.store.claimNextDue(this.deps.now());
      if (!rec) return;
      if (!(await this.upload(client, rec))) return;
    }
  }

  /** Returns false when the queue should stop for now (server unreachable or signed out). */
  private async upload(client: ApiClient, rec: LocalRecording): Promise<boolean> {
    const { store } = this.deps;
    if (!this.deps.fileExists(rec.filePath)) {
      await store.markFailed(rec.id, "The audio file is missing from this phone.");
      return true;
    }
    try {
      await client.uploadRecording(rec.id, this.deps.openFile(rec.filePath), rec.sha256, rec.mime, {
        createdAt: rec.createdAt,
        durationMs: rec.durationMs,
      });
      await store.markUploaded(rec.id);
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        await store.release(rec.id); // signed out: not this recording's fault
        return false;
      }
      if (e instanceof ApiError && PERMANENT.has(e.status)) {
        await store.markFailed(rec.id, `Rejected by the server (HTTP ${e.status}): ${e.message}`);
        return true;
      }
      const attempts = rec.uploadAttempts + 1;
      const reason = e instanceof ApiError ? `Server error (HTTP ${e.status})` : "Could not reach the server";
      await store.scheduleRetry(rec.id, attempts, this.deps.now() + backoffMs(attempts), reason);
      return false;
    }
  }
}
