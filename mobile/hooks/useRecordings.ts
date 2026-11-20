import { useQuery, useQueryClient } from "@tanstack/react-query";
import { File } from "expo-file-system";
import { useCallback, useEffect, useMemo, useRef } from "react";
import { clientFor } from "../services/api";
import { deleteEverywhere, mergeRecordings, ServerCache, type MergedRecording } from "../services/recordings";
import { getLocalServices } from "../services/uploads";
import { useSession } from "../stores/session";

const SERVER_POLL_MS = 5000;
const LOCAL_POLL_MS = 2000;

export interface RecordingsState {
  items: MergedRecording[];
  /** Recordings that have not reached the server yet. */
  pendingUploads: number;
  serverReachable: boolean;
  loading: boolean;
  remove(item: MergedRecording): Promise<void>;
}

/** Local rows (SQLite) and server rows (polled every 5s) merged by id. */
export function useRecordings(): RecordingsState {
  const serverUrl = useSession((s) => s.serverUrl);
  const queryClient = useQueryClient();
  const cache = useRef(new ServerCache()).current;

  const local = useQuery({
    queryKey: ["local-recordings"],
    refetchInterval: LOCAL_POLL_MS,
    queryFn: async () => (await getLocalServices()).store.list(),
  });

  const server = useQuery({
    queryKey: ["server-recordings", serverUrl],
    enabled: Boolean(serverUrl),
    refetchInterval: SERVER_POLL_MS,
    queryFn: () => cache.refresh(clientFor(serverUrl!)),
  });

  const items = useMemo(() => mergeRecordings(local.data ?? [], server.data?.items ?? []), [local.data, server.data]);

  // Remember the latest server status on the phone so it can still be shown offline.
  useEffect(() => {
    const known = server.data?.items;
    if (!known?.length || !local.data) return;
    void (async () => {
      const { store } = await getLocalServices();
      for (const s of known) {
        const row = local.data.find((l) => l.id === s.id);
        if (row && row.serverStatus !== s.processingStatus) await store.updateServerStatus(s.id, s.processingStatus);
      }
    })();
  }, [server.data, local.data]);

  const pendingUploads = items.filter((i) => i.uploadStatus === "QUEUED" || i.uploadStatus === "UPLOADING" || i.uploadStatus === "LOCAL_ONLY").length;

  const remove = useCallback(
    async (item: MergedRecording) => {
      if (!serverUrl) throw new Error("No server configured.");
      const { store } = await getLocalServices();
      await deleteEverywhere(item, {
        client: clientFor(serverUrl),
        store,
        deleteFile: (path) => {
          const file = new File(path);
          if (file.exists) file.delete();
        },
      });
      cache.forget(item.id);
      await queryClient.invalidateQueries({ queryKey: ["local-recordings"] });
      await queryClient.invalidateQueries({ queryKey: ["server-recordings"] });
    },
    [serverUrl, cache, queryClient],
  );

  return {
    items,
    pendingUploads,
    serverReachable: server.data?.reachable ?? true,
    loading: local.isPending,
    remove,
  };
}
