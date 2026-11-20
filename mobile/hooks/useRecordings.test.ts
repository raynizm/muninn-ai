import type { ApiClient, Recording } from "@muninn/api";
import type { LocalRecording } from "../services/db";
import { badgeFor, deleteEverywhere, mergeRecordings, ServerCache } from "../services/recordings";

const local = (over: Partial<LocalRecording> = {}): LocalRecording => ({
  id: "r1",
  filePath: "file:///docs/recordings/r1.m4a",
  mime: "audio/mp4",
  size: 1000,
  durationMs: 5000,
  sha256: "a".repeat(64),
  createdAt: "2026-11-20T09:00:00.000Z",
  uploadStatus: "UPLOADED",
  uploadAttempts: 0,
  lastError: null,
  serverStatus: "PENDING",
  nextAttemptAt: 0,
  ...over,
});

const server = (over: Partial<Recording> = {}): Recording => ({
  id: "r1",
  title: "Standup",
  createdAt: "2026-11-20T09:00:00Z",
  updatedAt: "2026-11-20T09:05:00Z",
  durationMs: 5000,
  mime: "audio/mp4",
  sizeBytes: 1000,
  uploadStatus: "UPLOADED",
  processingStatus: "COMPLETED",
  lastError: null,
  ...over,
});

describe("mergeRecordings", () => {
  it("mergesLocalAndServerById", () => {
    const items = mergeRecordings([local()], [server()]);

    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({
      id: "r1",
      title: "Standup", // server data wins
      processingStatus: "COMPLETED",
      onDevice: true,
      onServer: true,
      localPath: "file:///docs/recordings/r1.m4a",
    });
  });

  it("serverOnlyRecordingsAreIncluded", () => {
    const items = mergeRecordings([], [server({ id: "other" })]);
    expect(items).toMatchObject([{ id: "other", onDevice: false, onServer: true, localPath: null }]);
  });

  it("localOnlyItemsShownWithQueuedBadge", () => {
    const items = mergeRecordings([local({ uploadStatus: "QUEUED", serverStatus: null })], []);

    expect(items[0]).toMatchObject({ onDevice: true, onServer: false, processingStatus: null });
    expect(badgeFor(items[0])).toBe("Queued");
  });

  it("badgesDescribeUploadThenProcessing", () => {
    const badge = (l: Partial<LocalRecording>, s?: Partial<Recording>) =>
      badgeFor(mergeRecordings([local(l)], s ? [server(s)] : [])[0]);
    expect(badge({ uploadStatus: "LOCAL_ONLY", serverStatus: null })).toBe("On this phone");
    expect(badge({ uploadStatus: "UPLOADING", serverStatus: null })).toBe("Uploading");
    expect(badge({ uploadStatus: "FAILED", serverStatus: null })).toBe("Upload failed");
    expect(badge({}, { processingStatus: "TRANSCRIBING" })).toBe("Transcribing");
    expect(badge({}, { processingStatus: "COMPLETED" })).toBe("Ready");
    expect(badge({}, { processingStatus: "FAILED" })).toBe("Processing failed");
  });

  it("fallsBackToLastKnownServerStatusWhenOffline", () => {
    const items = mergeRecordings([local({ serverStatus: "SUMMARIZING" })], []);
    expect(items[0].processingStatus).toBe("SUMMARIZING");
  });

  it("sortsNewestFirst", () => {
    const items = mergeRecordings(
      [local({ id: "old", createdAt: "2026-11-19T09:00:00.000Z" }), local({ id: "new", createdAt: "2026-11-21T09:00:00.000Z" })],
      [],
    );
    expect(items.map((i) => i.id)).toEqual(["new", "old"]);
  });

  it("pendingUploadsCountsUnfinishedLocalRecordings", () => {
    const items = mergeRecordings(
      [
        local({ id: "a", uploadStatus: "QUEUED" }),
        local({ id: "b", uploadStatus: "UPLOADING" }),
        local({ id: "c", uploadStatus: "UPLOADED" }),
      ],
      [],
    );
    expect(items.filter((i) => i.uploadStatus === "QUEUED" || i.uploadStatus === "UPLOADING")).toHaveLength(2);
  });
});

describe("ServerCache", () => {
  it("serverUnreachableKeepsCachedItems", async () => {
    const cache = new ServerCache();
    const list = jest.fn().mockResolvedValueOnce([server({ id: "a" }), server({ id: "b" })]).mockRejectedValueOnce(new TypeError("network"));
    const client = { listRecordings: list } as unknown as ApiClient;

    const first = await cache.refresh(client);
    expect(first.reachable).toBe(true);
    expect(first.items.map((r) => r.id).sort()).toEqual(["a", "b"]);

    const second = await cache.refresh(client);
    expect(second.reachable).toBe(false);
    expect(second.items.map((r) => r.id).sort()).toEqual(["a", "b"]);
  });

  it("asksOnlyForChangesAfterTheFirstLoad", async () => {
    const cache = new ServerCache(100); // refresh in full every 100 polls
    const list = jest
      .fn()
      .mockResolvedValueOnce([server({ id: "a", updatedAt: "2026-11-20T09:00:00Z" })])
      .mockResolvedValueOnce([server({ id: "a", updatedAt: "2026-11-20T09:10:00Z", processingStatus: "FAILED" })]);
    const client = { listRecordings: list } as unknown as ApiClient;

    await cache.refresh(client);
    const second = await cache.refresh(client);

    expect(list).toHaveBeenNthCalledWith(1, undefined);
    expect(list).toHaveBeenNthCalledWith(2, "2026-11-20T09:00:00Z");
    expect(second.items).toHaveLength(1);
    expect(second.items[0].processingStatus).toBe("FAILED");
  });

  it("aFullRefreshDropsRecordingsDeletedElsewhere", async () => {
    const cache = new ServerCache(2);
    const list = jest
      .fn()
      .mockResolvedValueOnce([server({ id: "a" }), server({ id: "b" })])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([server({ id: "a" })]); // third poll is a full reload again
    const client = { listRecordings: list } as unknown as ApiClient;

    await cache.refresh(client);
    await cache.refresh(client);
    const third = await cache.refresh(client);

    expect(third.items.map((r) => r.id)).toEqual(["a"]);
  });
});

describe("deleteEverywhere", () => {
  const setup = (item: Partial<LocalRecording> | null) => {
    const calls: string[] = [];
    const client = {
      deleteRecording: jest.fn(async (id: string) => void calls.push(`server:${id}`)),
    } as unknown as ApiClient;
    const store = { delete: jest.fn(async (id: string) => void calls.push(`row:${id}`)) };
    const deleteFile = jest.fn((path: string) => void calls.push(`file:${path}`));
    return { calls, client, store, deleteFile, item: item ? local(item) : null };
  };

  it("deleteCallsServerThenRemovesLocal", async () => {
    const { calls, client, store, deleteFile } = setup({});

    await deleteEverywhere({ id: "r1", onServer: true, localPath: "file:///docs/recordings/r1.m4a" }, { client, store, deleteFile });

    expect(calls).toEqual(["server:r1", "row:r1", "file:file:///docs/recordings/r1.m4a"]);
  });

  it("keepsEverythingWhenTheServerCannotBeReached", async () => {
    const { calls, client, store, deleteFile } = setup({});
    (client.deleteRecording as jest.Mock).mockRejectedValueOnce(new TypeError("network"));

    await expect(
      deleteEverywhere({ id: "r1", onServer: true, localPath: "file:///x.m4a" }, { client, store, deleteFile }),
    ).rejects.toThrow();

    expect(calls).toEqual([]);
    expect(store.delete).not.toHaveBeenCalled();
    expect(deleteFile).not.toHaveBeenCalled();
  });

  it("skipsTheServerForRecordingsThatNeverLeftThePhone", async () => {
    const { calls, client, store, deleteFile } = setup({});

    await deleteEverywhere({ id: "r1", onServer: false, localPath: "file:///x.m4a" }, { client, store, deleteFile });

    expect(client.deleteRecording).not.toHaveBeenCalled();
    expect(calls).toEqual(["row:r1", "file:file:///x.m4a"]);
  });

  it("treatsAlreadyDeletedOnServerAsDone", async () => {
    const { client, store, deleteFile } = setup({});
    const { ApiError } = jest.requireActual("@muninn/api");
    (client.deleteRecording as jest.Mock).mockRejectedValueOnce(new ApiError(404, "gone"));

    await deleteEverywhere({ id: "r1", onServer: true, localPath: "file:///x.m4a" }, { client, store, deleteFile });

    expect(store.delete).toHaveBeenCalledWith("r1");
  });
});
