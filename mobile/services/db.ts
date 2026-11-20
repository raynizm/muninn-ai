import type { ProcessingStatus } from "@muninn/api";

export type UploadStatus = "LOCAL_ONLY" | "QUEUED" | "UPLOADING" | "UPLOADED" | "FAILED";

/** The slice of expo-sqlite's SQLiteDatabase the app uses, so tests can run on another SQLite. */
export interface SqlDb {
  execAsync(sql: string): Promise<void>;
  runAsync(sql: string, ...params: unknown[]): Promise<{ changes: number }>;
  getAllAsync<T>(sql: string, ...params: unknown[]): Promise<T[]>;
  getFirstAsync<T>(sql: string, ...params: unknown[]): Promise<T | null>;
}

export interface LocalRecording {
  id: string;
  filePath: string;
  mime: string;
  size: number;
  durationMs: number;
  sha256: string;
  /** ISO-8601 instant the recording started. */
  createdAt: string;
  uploadStatus: UploadStatus;
  uploadAttempts: number;
  lastError: string | null;
  /** Processing status last reported by the server, once uploaded. */
  serverStatus: ProcessingStatus | null;
  /** Epoch milliseconds before which the upload must not be retried. */
  nextAttemptAt: number;
}

export type NewLocalRecording = Pick<LocalRecording, "id" | "filePath" | "mime" | "size" | "durationMs" | "sha256" | "createdAt">;

interface Row {
  id: string;
  file_path: string;
  mime: string;
  size: number;
  duration_ms: number;
  sha256: string;
  created_at: string;
  upload_status: UploadStatus;
  upload_attempts: number;
  last_error: string | null;
  server_status: ProcessingStatus | null;
  next_attempt_at: number;
}

const toRecording = (r: Row): LocalRecording => ({
  id: r.id,
  filePath: r.file_path,
  mime: r.mime,
  size: r.size,
  durationMs: r.duration_ms,
  sha256: r.sha256,
  createdAt: r.created_at,
  uploadStatus: r.upload_status,
  uploadAttempts: r.upload_attempts,
  lastError: r.last_error,
  serverStatus: r.server_status,
  nextAttemptAt: r.next_attempt_at,
});

/** Creates the schema if needed. Safe to call on every start. */
export async function migrate(db: SqlDb): Promise<void> {
  await db.execAsync(`
    CREATE TABLE IF NOT EXISTS local_recording (
      id TEXT PRIMARY KEY NOT NULL,
      file_path TEXT NOT NULL,
      mime TEXT NOT NULL,
      size INTEGER NOT NULL,
      duration_ms INTEGER NOT NULL,
      sha256 TEXT NOT NULL,
      created_at TEXT NOT NULL,
      upload_status TEXT NOT NULL DEFAULT 'LOCAL_ONLY'
        CHECK (upload_status IN ('LOCAL_ONLY','QUEUED','UPLOADING','UPLOADED','FAILED')),
      upload_attempts INTEGER NOT NULL DEFAULT 0,
      last_error TEXT,
      server_status TEXT,
      next_attempt_at INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX IF NOT EXISTS local_recording_queue ON local_recording (upload_status, next_attempt_at, created_at);
  `);
}

/** Opens the app database on the device. */
export async function openDatabase(): Promise<SqlDb> {
  const SQLite = await import("expo-sqlite");
  const db = await SQLite.openDatabaseAsync("muninn.db");
  await db.execAsync("PRAGMA journal_mode = WAL;");
  await migrate(db);
  return db;
}

/** Local recordings and their upload state. Every state change is a single statement. */
export class RecordingStore {
  constructor(private readonly db: SqlDb) {}

  async insert(r: NewLocalRecording): Promise<void> {
    await this.db.runAsync(
      `INSERT INTO local_recording (id, file_path, mime, size, duration_ms, sha256, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
      r.id,
      r.filePath,
      r.mime,
      r.size,
      r.durationMs,
      r.sha256,
      r.createdAt,
    );
  }

  async get(id: string): Promise<LocalRecording | null> {
    const row = await this.db.getFirstAsync<Row>("SELECT * FROM local_recording WHERE id = ?", id);
    return row ? toRecording(row) : null;
  }

  async list(): Promise<LocalRecording[]> {
    const rows = await this.db.getAllAsync<Row>("SELECT * FROM local_recording ORDER BY created_at DESC");
    return rows.map(toRecording);
  }

  /** Number of recordings still waiting to reach the server. */
  async countPending(): Promise<number> {
    const row = await this.db.getFirstAsync<{ n: number }>(
      "SELECT COUNT(*) AS n FROM local_recording WHERE upload_status IN ('LOCAL_ONLY','QUEUED','UPLOADING')",
    );
    return row?.n ?? 0;
  }

  /** LOCAL_ONLY or FAILED -> QUEUED, due immediately. */
  async markQueued(id: string): Promise<void> {
    await this.db.runAsync(
      `UPDATE local_recording SET upload_status = 'QUEUED', next_attempt_at = 0, last_error = NULL
       WHERE id = ? AND upload_status IN ('LOCAL_ONLY','FAILED','QUEUED')`,
      id,
    );
  }

  /** Takes the oldest due QUEUED recording and marks it UPLOADING, or returns null. */
  async claimNextDue(now: number): Promise<LocalRecording | null> {
    const next = await this.db.getFirstAsync<{ id: string }>(
      `SELECT id FROM local_recording WHERE upload_status = 'QUEUED' AND next_attempt_at <= ?
       ORDER BY created_at ASC LIMIT 1`,
      now,
    );
    if (!next) return null;
    const { changes } = await this.db.runAsync(
      "UPDATE local_recording SET upload_status = 'UPLOADING' WHERE id = ? AND upload_status = 'QUEUED'",
      next.id,
    );
    return changes === 1 ? this.get(next.id) : null;
  }

  async markUploaded(id: string): Promise<void> {
    await this.db.runAsync(
      "UPDATE local_recording SET upload_status = 'UPLOADED', last_error = NULL, server_status = COALESCE(server_status, 'PENDING') WHERE id = ?",
      id,
    );
  }

  async scheduleRetry(id: string, attempts: number, nextAttemptAt: number, error: string): Promise<void> {
    await this.db.runAsync(
      "UPDATE local_recording SET upload_status = 'QUEUED', upload_attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
      attempts,
      nextAttemptAt,
      error,
      id,
    );
  }

  /** Back to QUEUED without counting an attempt (for example while signed out). */
  async release(id: string): Promise<void> {
    await this.db.runAsync("UPDATE local_recording SET upload_status = 'QUEUED' WHERE id = ? AND upload_status = 'UPLOADING'", id);
  }

  async markFailed(id: string, error: string): Promise<void> {
    await this.db.runAsync("UPDATE local_recording SET upload_status = 'FAILED', last_error = ? WHERE id = ?", error, id);
  }

  /** After a crash or kill, nothing is really uploading: put those rows back in the queue. */
  async resetStaleUploading(): Promise<number> {
    const { changes } = await this.db.runAsync(
      "UPDATE local_recording SET upload_status = 'QUEUED', next_attempt_at = 0 WHERE upload_status = 'UPLOADING'",
    );
    return changes;
  }

  async updateServerStatus(id: string, status: ProcessingStatus): Promise<void> {
    await this.db.runAsync("UPDATE local_recording SET server_status = ? WHERE id = ?", status, id);
  }

  async delete(id: string): Promise<void> {
    await this.db.runAsync("DELETE FROM local_recording WHERE id = ?", id);
  }
}
