import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useRef } from "react";
import type { Recording } from "@muninn/api";
import { useApi } from "../api";

const POLL_MS = 5000;
/** updatedSince never reports deletions, so every Nth poll reloads the whole list. */
const FULL_REFRESH_EVERY = 6;
const KEY = ["recordings"];

/** Recordings kept in sync by polling `GET /api/recordings?updatedSince=` every 5 seconds. */
export function useRecordings() {
  const api = useApi();
  const queryClient = useQueryClient();
  const known = useRef(new Map<string, Recording>());
  const cursor = useRef<string | undefined>(undefined);
  const polls = useRef(0);

  const query = useQuery({
    queryKey: KEY,
    refetchInterval: POLL_MS,
    queryFn: async () => {
      const full = polls.current % FULL_REFRESH_EVERY === 0;
      polls.current += 1;
      const changed = await api.listRecordings(full ? undefined : cursor.current);
      if (full) known.current.clear();
      for (const r of changed) known.current.set(r.id, r);
      for (const r of known.current.values()) {
        if (!cursor.current || r.updatedAt > cursor.current) cursor.current = r.updatedAt;
      }
      return [...known.current.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    },
  });

  const remove = useCallback(
    async (id: string) => {
      await api.deleteRecording(id);
      known.current.delete(id);
      queryClient.setQueryData<Recording[]>(KEY, (old) => old?.filter((r) => r.id !== id));
    },
    [api, queryClient],
  );

  return { ...query, remove };
}
