import { useQuery } from "@tanstack/react-query";
import { useAudioPlayer, useAudioPlayerStatus } from "expo-audio";
import { router, useLocalSearchParams } from "expo-router";
import { Alert, ScrollView, StyleSheet, Text, View } from "react-native";
import { Button } from "../../components/Button";
import { formatDate, formatDuration } from "../../components/RecordingRow";
import { useTheme } from "../../components/theme";
import { useRecordings } from "../../hooks/useRecordings";
import { clientFor } from "../../services/api";
import { badgeFor } from "../../services/recordings";
import { useSession } from "../../stores/session";

const clock = (seconds: number) => formatDuration(seconds * 1000);

export default function RecordingDetail() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const t = useTheme();
  const serverUrl = useSession((s) => s.serverUrl);
  const { items, remove } = useRecordings();
  const item = items.find((i) => i.id === id);

  const detail = useQuery({
    queryKey: ["recording-detail", serverUrl, id],
    enabled: Boolean(serverUrl && item?.onServer),
    queryFn: () => clientFor(serverUrl!).getRecording(id),
    refetchInterval: (q) => (q.state.data?.processingStatus === "COMPLETED" || q.state.data?.processingStatus === "FAILED" ? false : 5000),
  });

  // The audio is played from the phone's own copy; the server copy needs a signed-in request.
  const player = useAudioPlayer(item?.localPath ?? null);
  const status = useAudioPlayerStatus(player);

  if (!item) {
    return (
      <View style={[styles.screen, { backgroundColor: t.bg }]}>
        <Text style={{ color: t.muted }}>This recording is no longer available.</Text>
      </View>
    );
  }

  const d = detail.data;
  const confirmDelete = () =>
    Alert.alert("Delete recording?", item.onServer ? "It is removed from the server and from this phone." : "It is removed from this phone.", [
      { text: "Cancel", style: "cancel" },
      {
        text: "Delete",
        style: "destructive",
        onPress: () =>
          remove(item)
            .then(() => router.back())
            .catch(() => Alert.alert("Could not delete", "The server could not be reached. Nothing was removed.")),
      },
    ]);

  return (
    <ScrollView style={{ backgroundColor: t.bg }} contentContainerStyle={styles.screen}>
      <Text style={[styles.title, { color: t.text }]}>{item.title ?? "Untitled recording"}</Text>
      <Text style={{ color: t.muted }}>
        {formatDate(item.createdAt)}
        {item.durationMs != null ? ` · ${formatDuration(item.durationMs)}` : ""} · {badgeFor(item)}
      </Text>
      {item.lastError ? <Text style={{ color: t.error }}>{item.lastError}</Text> : null}

      {item.localPath ? (
        <Button
          title={status.playing ? "Pause" : "Play audio"}
          variant="plain"
          onPress={() => (status.playing ? player.pause() : player.play())}
        />
      ) : (
        <Text style={{ color: t.muted }}>The audio is only on the server.</Text>
      )}

      {d?.summary && (
        <View style={styles.section}>
          <Text style={[styles.heading, { color: t.text }]}>Summary</Text>
          <Text style={{ color: t.text }}>{d.summary.text}</Text>
          {d.summary.actionItems.map((a, i) => (
            <Text key={i} style={{ color: t.text }}>
              • {a}
            </Text>
          ))}
        </View>
      )}

      {d?.transcript && (
        <View style={styles.section}>
          <Text style={[styles.heading, { color: t.text }]}>Transcript</Text>
          {d.transcript.segments.length > 0
            ? d.transcript.segments.map((s, i) => (
                <Text key={i} style={{ color: t.text }}>
                  <Text style={{ color: t.muted }}>{clock(s.start)}  </Text>
                  {s.text}
                </Text>
              ))
            : <Text style={{ color: t.text }}>{d.transcript.text}</Text>}
        </View>
      )}

      {item.onServer && !d && !detail.isError && <Text style={{ color: t.muted }}>Loading…</Text>}
      {!item.onServer && <Text style={{ color: t.muted }}>The transcript appears here after the recording is uploaded.</Text>}

      <Button title="Delete" variant="danger" onPress={confirmDelete} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { padding: 20, gap: 12, flexGrow: 1 },
  title: { fontSize: 22, fontWeight: "700" },
  section: { gap: 6, marginTop: 8 },
  heading: { fontSize: 18, fontWeight: "600" },
});
