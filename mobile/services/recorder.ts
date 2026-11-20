import { AudioModule, RecordingPresets, requestRecordingPermissionsAsync, setAudioModeAsync } from "expo-audio";
import * as Crypto from "expo-crypto";
import { Directory, File, Paths } from "expo-file-system";
import type { LocalRecording, RecordingStore } from "./db";
import { sha256OfFile } from "./hash";

export const RECORDING_MIME = "audio/mp4";

type AudioRecorder = InstanceType<typeof AudioModule.AudioRecorder>;

interface Active {
  recorder: AudioRecorder;
  id: string;
  startedAt: Date;
}

/**
 * Records AAC/M4A. The file is moved from the cache into the persistent document directory and stored in
 * SQLite before `stop()` returns, so a finished recording is never lost.
 */
export function createRecorder(store: RecordingStore) {
  let active: Active | null = null;

  return {
    isRecording: () => active !== null,

    /** Milliseconds recorded so far (0 when idle). */
    elapsedMs: () => active?.recorder.getStatus().durationMillis ?? 0,

    async start(): Promise<{ id: string }> {
      if (active) throw new Error("Already recording.");
      const permission = await requestRecordingPermissionsAsync();
      if (!permission.granted) throw new Error("Muninn needs the microphone permission to record.");
      await setAudioModeAsync({ allowsRecording: true, playsInSilentMode: true });
      const recorder = new AudioModule.AudioRecorder(RecordingPresets.HIGH_QUALITY);
      await recorder.prepareToRecordAsync();
      recorder.record();
      active = { recorder, id: Crypto.randomUUID(), startedAt: new Date() };
      return { id: active.id };
    },

    async stop(): Promise<LocalRecording> {
      if (!active) throw new Error("Not recording.");
      const { recorder, id, startedAt } = active;
      const durationMs = recorder.getStatus().durationMillis;
      await recorder.stop();
      active = null;
      await setAudioModeAsync({ allowsRecording: false });
      if (!recorder.uri) throw new Error("The recording produced no file.");

      const dir = new Directory(Paths.document, "recordings");
      dir.create({ intermediates: true, idempotent: true });
      const dest = new File(dir, `${id}.m4a`);
      await new File(recorder.uri).move(dest);

      await store.insert({
        id,
        filePath: dest.uri,
        mime: RECORDING_MIME,
        size: dest.size ?? 0,
        durationMs,
        sha256: await sha256OfFile(dest),
        createdAt: startedAt.toISOString(),
      });
      const saved = await store.get(id);
      if (!saved) throw new Error("The recording could not be saved.");
      return saved;
    },
  };
}
