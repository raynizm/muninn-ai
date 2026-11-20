import { Link } from "expo-router";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { badgeFor, type MergedRecording } from "../services/recordings";
import { useTheme } from "./theme";

export const formatDuration = (ms: number | null) => {
  if (ms == null) return "";
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};

export const formatDate = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });

export function RecordingRow({ item }: { item: MergedRecording }) {
  const t = useTheme();
  const failed = item.uploadStatus === "FAILED" || item.processingStatus === "FAILED";
  return (
    <Link href={{ pathname: "/recordings/[id]", params: { id: item.id } }} asChild>
      <Pressable style={[styles.row, { backgroundColor: t.surface, borderColor: t.border }]}>
        <View style={styles.main}>
          <Text style={[styles.title, { color: t.text }]} numberOfLines={1}>
            {item.title ?? "Untitled recording"}
          </Text>
          <Text style={{ color: t.muted, fontSize: 13 }}>
            {formatDate(item.createdAt)}
            {item.durationMs != null ? ` · ${formatDuration(item.durationMs)}` : ""}
          </Text>
          {failed && item.lastError ? <Text style={{ color: t.error, fontSize: 13 }}>{item.lastError}</Text> : null}
        </View>
        <Text style={[styles.badge, { color: failed ? t.error : item.processingStatus === "COMPLETED" ? t.ok : t.muted, borderColor: t.border }]}>
          {badgeFor(item)}
        </Text>
      </Pressable>
    </Link>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center", gap: 12, padding: 14, borderRadius: 12, borderWidth: 1 },
  main: { flex: 1, gap: 2 },
  title: { fontSize: 16, fontWeight: "600" },
  badge: { fontSize: 12, borderWidth: 1, borderRadius: 999, paddingHorizontal: 10, paddingVertical: 3, overflow: "hidden" },
});
