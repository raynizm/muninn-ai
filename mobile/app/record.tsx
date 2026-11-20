import { router } from "expo-router";
import { useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { Button } from "../components/Button";
import { RecordButton } from "../components/RecordButton";
import { useTheme } from "../components/theme";
import { getLocalServices, drainUploads } from "../services/uploads";
import { useSession } from "../stores/session";

const clock = (ms: number) => {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};

export default function Record() {
  const t = useTheme();
  const serverUrl = useSession((s) => s.serverUrl);
  const [recording, setRecording] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(
    () => () => {
      if (timer.current) clearInterval(timer.current);
    },
    [],
  );

  const toggle = async () => {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const { recorder, queue } = await getLocalServices();
      if (!recording) {
        await recorder.start();
        setRecording(true);
        timer.current = setInterval(() => setElapsed(recorder.elapsedMs()), 250);
      } else {
        if (timer.current) clearInterval(timer.current);
        const saved = await recorder.stop();
        setRecording(false);
        setElapsed(0);
        await queue.enqueue(saved.id);
        setMessage("Saved on this phone. It uploads when the server is reachable.");
        if (serverUrl) void drainUploads(serverUrl);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Recording failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={[styles.screen, { backgroundColor: t.bg }]}>
      <View style={styles.indicator}>
        <View style={[styles.light, { backgroundColor: recording ? t.error : t.border }]} />
        <Text style={{ color: recording ? t.error : t.muted, fontWeight: "600" }}>
          {recording ? "Recording" : "Not recording"}
        </Text>
      </View>
      <Text style={[styles.time, { color: t.text }]}>{clock(elapsed)}</Text>
      <RecordButton recording={recording} disabled={busy} onPress={() => void toggle()} />
      {message && <Text style={{ color: t.ok, textAlign: "center" }}>{message}</Text>}
      {error && <Text style={{ color: t.error, textAlign: "center" }}>{error}</Text>}
      {!recording && <Button title="Done" variant="plain" onPress={() => router.back()} />}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, alignItems: "center", justifyContent: "center", gap: 24, padding: 24 },
  indicator: { flexDirection: "row", alignItems: "center", gap: 8 },
  light: { width: 12, height: 12, borderRadius: 6 },
  time: { fontSize: 56, fontVariant: ["tabular-nums"], fontWeight: "300" },
});
