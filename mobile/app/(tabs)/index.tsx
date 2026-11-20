import { router } from "expo-router";
import { StyleSheet, Text, View } from "react-native";
import { Button } from "../../components/Button";
import { useTheme } from "../../components/theme";
import { useSession } from "../../stores/session";

export default function Home() {
  const t = useTheme();
  const user = useSession((s) => s.user);
  return (
    <View style={[styles.screen, { backgroundColor: t.bg }]}>
      <Text style={[styles.title, { color: t.text }]}>Hello{user?.displayName ? `, ${user.displayName}` : ""}</Text>
      <Button title="Record" onPress={() => router.push("/record")} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, padding: 24, gap: 16 },
  title: { fontSize: 24, fontWeight: "700" },
});
