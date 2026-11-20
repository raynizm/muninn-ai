import { FlatList, StyleSheet, Text, View } from "react-native";
import { RecordingRow } from "../../components/RecordingRow";
import { useTheme } from "../../components/theme";
import { useRecordings } from "../../hooks/useRecordings";

export default function Recordings() {
  const t = useTheme();
  const { items, loading, serverReachable } = useRecordings();
  return (
    <View style={[styles.screen, { backgroundColor: t.bg }]}>
      {!serverReachable && <Text style={{ color: t.muted }}>Server not reachable. Showing what this phone knows.</Text>}
      <FlatList
        data={items}
        keyExtractor={(i) => i.id}
        renderItem={({ item }) => <RecordingRow item={item} />}
        ItemSeparatorComponent={() => <View style={{ height: 8 }} />}
        ListEmptyComponent={
          <Text style={{ color: t.muted }}>{loading ? "Loading…" : "No recordings yet. Press Record on the Home tab."}</Text>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({ screen: { flex: 1, padding: 16, gap: 12 } });
