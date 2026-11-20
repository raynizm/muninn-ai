import { File } from "expo-file-system";
import * as Network from "expo-network";
import { clientFor } from "./api";
import { openDatabase, RecordingStore } from "./db";
import { createRecorder } from "./recorder";
import { UploadQueue } from "./queue";

export interface LocalServices {
  store: RecordingStore;
  queue: UploadQueue;
  recorder: ReturnType<typeof createRecorder>;
}

let services: Promise<LocalServices> | null = null;

/** Opens the database once and wires the store, upload queue and recorder together. */
export function getLocalServices(): Promise<LocalServices> {
  services ??= (async () => {
    const store = new RecordingStore(await openDatabase());
    const queue = new UploadQueue({
      store,
      isOnline: async () => {
        const state = await Network.getNetworkStateAsync();
        return Boolean(state.isConnected) && state.isInternetReachable !== false;
      },
      now: () => Date.now(),
      openFile: (path) => new File(path) as unknown as BodyInit,
      fileExists: (path) => new File(path).exists,
    });
    await queue.recoverOnStart();
    return { store, queue, recorder: createRecorder(store) };
  })();
  return services;
}

/** Uploads everything that is due against the given server. Never throws. */
export async function drainUploads(serverUrl: string): Promise<void> {
  try {
    const { queue } = await getLocalServices();
    await queue.drain(clientFor(serverUrl));
  } catch {
    // Offline or a transient failure: the queue keeps its state and the next trigger retries.
  }
}
