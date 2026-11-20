import { router } from "expo-router";
import { ScrollView, StyleSheet, Text, View } from "react-native";
import { Button } from "../../components/Button";
import { RecordingRow } from "../../components/RecordingRow";
import { useTheme } from "../../components/theme";
import { useRecordings } from "../../hooks/useRecordings";
import { useSession } from "../../stores/session";

export default function Home() {
  const t = useTheme();
  const user = useSession((s) => s.user);
  const { items, pendingUploads, serverReachable } = useRecordings();

  return (
    <ScrollView style={{ backgroundColor: t.bg }} contentContainerStyle={styles.screen}>
      <Text style={[styles.title, { color: t.text }]}>Hello{user?.displayName ? `, ${user.displayName}` : ""}</Text>

      <View style={[styles.status, { backgroundColor: t.surface, borderColor: t.border }]}>
        <Text style={{ color: serverReachable ? t.ok : t.error }}>
          {serverReachable ? "Connected to your server" : "Server not reachable"}
        </Text>
        <Text style={{ color: t.muted }}>
          {pendingUploads === 0
            ? "Everything is uploaded"
            : `${pendingUploads} recording${pendingUploads === 1 ? "" : "s"} waiting to upload`}
        </Text>
      </View>

      <Button title="Record" onPress={() => router.push("/record")} />

      <Text style={[styles.section, { color: t.text }]}>Recent</Text>
      {items.length === 0 ? (
        <Text style={{ color: t.muted }}>No recordings yet.</Text>
      ) : (
        items.slice(0, 5).map((item) => <RecordingRow key={item.id} item={item} />)
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { padding: 20, gap: 16 },
  title: { fontSize: 24, fontWeight: "700" },
  status: { padding: 14, borderRadius: 12, borderWidth: 1, gap: 4 },
  section: { fontSize: 18, fontWeight: "600", marginTop: 8 },
});
