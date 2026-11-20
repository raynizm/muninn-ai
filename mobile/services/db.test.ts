import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { openTestDb } from "../test-utils/sqliteDb";
import { migrate, RecordingStore, type NewLocalRecording } from "./db";

const sample = (over: Partial<NewLocalRecording> = {}): NewLocalRecording => ({
  id: "11111111-1111-4111-8111-111111111111",
  filePath: "file:///docs/recordings/a.m4a",
  mime: "audio/mp4",
  size: 4096,
  durationMs: 12_000,
  sha256: "a".repeat(64),
  createdAt: "2026-11-20T09:00:00.000Z",
  ...over,
});

describe("RecordingStore", () => {
  it("recordingSurvivesRestartAsQueued", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "muninn-db-"));
    const file = path.join(dir, "muninn.db");

    const first = openTestDb(file);
    await migrate(first);
    const store1 = new RecordingStore(first);
    await store1.insert(sample());
    await store1.markQueued(sample().id);
    first.close();

    const second = openTestDb(file); // "app restart": new connection on the same file
    await migrate(second);
    const store2 = new RecordingStore(second);
    const rec = await store2.get(sample().id);
    expect(rec).toMatchObject({
      id: sample().id,
      uploadStatus: "QUEUED",
      filePath: sample().filePath,
      sha256: sample().sha256,
      size: 4096,
      durationMs: 12_000,
      uploadAttempts: 0,
    });
    second.close();
  });

  it("newRecordingsStartLocalOnly", async () => {
    const db = openTestDb();
    await migrate(db);
    const store = new RecordingStore(db);
    await store.insert(sample());
    expect((await store.get(sample().id))?.uploadStatus).toBe("LOCAL_ONLY");
  });

  it("staleUploadingResetOnStart", async () => {
    const db = openTestDb();
    await migrate(db);
    const store = new RecordingStore(db);
    await store.insert(sample({ id: "a" }));
    await store.insert(sample({ id: "b" }));
    await store.markQueued("a");
    await store.markQueued("b");
    await store.claimNextDue(Number.MAX_SAFE_INTEGER); // "a" is now UPLOADING when the app dies

    expect(await store.resetStaleUploading()).toBe(1);

    expect((await store.get("a"))?.uploadStatus).toBe("QUEUED");
    expect((await store.get("b"))?.uploadStatus).toBe("QUEUED");
  });

  it("claimsOldestDueRecordingFirstAndSkipsNotDueOnes", async () => {
    const db = openTestDb();
    await migrate(db);
    const store = new RecordingStore(db);
    await store.insert(sample({ id: "old", createdAt: "2026-11-20T08:00:00.000Z" }));
    await store.insert(sample({ id: "new", createdAt: "2026-11-20T09:00:00.000Z" }));
    await store.insert(sample({ id: "later", createdAt: "2026-11-20T07:00:00.000Z" }));
    for (const id of ["old", "new", "later"]) await store.markQueued(id);
    await store.scheduleRetry("later", 1, 10_000, "HTTP 500");

    expect((await store.claimNextDue(1_000))?.id).toBe("old");
    expect((await store.claimNextDue(1_000))?.id).toBe("new");
    expect(await store.claimNextDue(1_000)).toBeNull(); // "later" is not due yet
    expect((await store.claimNextDue(10_000))?.id).toBe("later");
  });

  it("migrateIsIdempotent", async () => {
    const db = openTestDb();
    await migrate(db);
    await migrate(db);
    const store = new RecordingStore(db);
    await store.insert(sample());
    expect(await store.list()).toHaveLength(1);
  });
});
