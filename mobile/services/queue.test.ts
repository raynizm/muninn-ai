import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { ApiError, type ApiClient } from "@muninn/api";
import { openTestDb } from "../test-utils/sqliteDb";
import { migrate, RecordingStore, type NewLocalRecording } from "./db";
import { backoffMs, UploadQueue } from "./queue";

const ID = "11111111-1111-4111-8111-111111111111";

const sample = (over: Partial<NewLocalRecording> = {}): NewLocalRecording => ({
  id: ID,
  filePath: "file:///docs/recordings/a.m4a",
  mime: "audio/mp4",
  size: 4096,
  durationMs: 12_000,
  sha256: "a".repeat(64),
  createdAt: "2026-11-20T09:00:00.000Z",
  ...over,
});

async function setup(opts: { online?: boolean; rec?: Partial<NewLocalRecording> } = {}) {
  const db = openTestDb();
  await migrate(db);
  const store = new RecordingStore(db);
  await store.insert(sample(opts.rec));
  let clock = 1_000_000;
  const upload = jest.fn();
  const client = { uploadRecording: upload } as unknown as ApiClient;
  const queue = new UploadQueue({
    store,
    isOnline: async () => opts.online ?? true,
    now: () => clock,
    openFile: (p) => ({ path: p }) as unknown as BodyInit,
    fileExists: () => true,
  });
  return { store, queue, client, upload, advance: (ms: number) => (clock += ms), now: () => clock };
}

describe("UploadQueue", () => {
  it("drainRetriesWithBackoffAfter500", async () => {
    const { store, queue, client, upload, advance } = await setup();
    upload.mockRejectedValueOnce(new ApiError(500, "boom")).mockResolvedValueOnce({ id: ID });
    await queue.enqueue(ID);

    await queue.drain(client);
    let rec = (await store.get(ID))!;
    expect(rec.uploadStatus).toBe("QUEUED");
    expect(rec.uploadAttempts).toBe(1);
    expect(rec.lastError).toMatch(/500/);

    await queue.drain(client); // backoff has not elapsed: nothing happens
    expect(upload).toHaveBeenCalledTimes(1);

    advance(backoffMs(1));
    await queue.drain(client);
    rec = (await store.get(ID))!;
    expect(upload).toHaveBeenCalledTimes(2);
    expect(rec.uploadStatus).toBe("UPLOADED");
    expect(rec.lastError).toBeNull();
  });

  it("backoffGrowsExponentiallyAndIsCapped", () => {
    expect(backoffMs(2)).toBe(backoffMs(1) * 2);
    expect(backoffMs(3)).toBe(backoffMs(1) * 4);
    expect(backoffMs(50)).toBe(15 * 60_000);
  });

  it("drainTreatsDuplicateOkAsUploaded", async () => {
    const { store, queue, client, upload } = await setup();
    // The server answers 200 (not 201) when the same bytes were already stored under this id.
    upload.mockResolvedValue({ id: ID });
    await queue.enqueue(ID);

    await queue.drain(client);

    expect((await store.get(ID))?.uploadStatus).toBe("UPLOADED");
    expect((await store.get(ID))?.serverStatus).toBe("PENDING");
  });

  it("failsPermanentlyWhenTheServerSaysTheIdIsTakenByDifferentBytes", async () => {
    const { store, queue, client, upload } = await setup();
    upload.mockRejectedValue(new ApiError(409, "conflict"));
    await queue.enqueue(ID);

    await queue.drain(client);

    const rec = (await store.get(ID))!;
    expect(rec.uploadStatus).toBe("FAILED");
    expect(rec.lastError).toMatch(/409|conflict/i);
  });

  it("drainDoesNothingWhenOffline", async () => {
    const { store, queue, client, upload } = await setup({ online: false });
    await queue.enqueue(ID);

    await queue.drain(client);

    expect(upload).not.toHaveBeenCalled();
    expect((await store.get(ID))?.uploadStatus).toBe("QUEUED");
  });

  it("keepsRecordingsQueuedWhenSignedOut", async () => {
    const { store, queue, client, upload } = await setup();
    upload.mockRejectedValue(new ApiError(401, "unauthorized"));
    await queue.enqueue(ID);

    await queue.drain(client);

    const rec = (await store.get(ID))!;
    expect(rec.uploadStatus).toBe("QUEUED");
    expect(rec.uploadAttempts).toBe(0); // not the recording's fault
  });

  it("staleUploadingResetOnStart", async () => {
    const { store, queue } = await setup();
    await queue.enqueue(ID);
    await store.claimNextDue(Number.MAX_SAFE_INTEGER); // app was killed mid-upload
    expect((await store.get(ID))?.uploadStatus).toBe("UPLOADING");

    await queue.recoverOnStart();

    expect((await store.get(ID))?.uploadStatus).toBe("QUEUED");
  });

  it("uploadsOneAtATimeInCreationOrder", async () => {
    const { store, queue, client, upload } = await setup();
    await store.insert(sample({ id: "second", createdAt: "2026-11-20T10:00:00.000Z" }));
    await queue.enqueue("second");
    await queue.enqueue(ID);
    let running = 0;
    let maxRunning = 0;
    const order: string[] = [];
    upload.mockImplementation(async (id: string) => {
      running++;
      maxRunning = Math.max(maxRunning, running);
      order.push(id);
      await new Promise((r) => setTimeout(r, 5));
      running--;
      return { id };
    });

    await Promise.all([queue.drain(client), queue.drain(client)]); // a second trigger must not double-upload

    expect(order).toEqual([ID, "second"]);
    expect(maxRunning).toBe(1);
  });

  it("failsWhenTheAudioFileIsGone", async () => {
    const db = openTestDb();
    await migrate(db);
    const store = new RecordingStore(db);
    await store.insert(sample());
    const upload = jest.fn();
    const queue = new UploadQueue({
      store,
      isOnline: async () => true,
      now: () => 1,
      openFile: () => ({}) as BodyInit,
      fileExists: () => false,
    });
    await queue.enqueue(ID);

    await queue.drain({ uploadRecording: upload } as unknown as ApiClient);

    expect(upload).not.toHaveBeenCalled();
    expect((await store.get(ID))?.uploadStatus).toBe("FAILED");
  });

  it("fileNeverDeletedAfterUpload", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "muninn-audio-"));
    const audio = path.join(dir, "a.m4a");
    fs.writeFileSync(audio, "audio-bytes");
    const { store, queue, client, upload } = await setup({ rec: { filePath: audio } });
    upload.mockResolvedValue({ id: ID });
    await queue.enqueue(ID);

    await queue.drain(client);

    expect((await store.get(ID))?.uploadStatus).toBe("UPLOADED");
    expect(fs.existsSync(audio)).toBe(true); // audio stays on the phone until the user deletes it
    expect((await store.get(ID))?.filePath).toBe(audio);
  });

  it("sendsMetadataButNoTitle", async () => {
    const { queue, client, upload } = await setup();
    upload.mockResolvedValue({ id: ID });
    await queue.enqueue(ID);

    await queue.drain(client);

    expect(upload).toHaveBeenCalledWith(ID, expect.anything(), "a".repeat(64), "audio/mp4", {
      createdAt: "2026-11-20T09:00:00.000Z",
      durationMs: 12_000,
    });
  });
});
