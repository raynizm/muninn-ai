import { StyleSheet, Text, View } from "react-native";
import { Button } from "../../components/Button";
import { useTheme } from "../../components/theme";
import { useSession } from "../../stores/session";

export default function Settings() {
  const t = useTheme();
  const { user, serverUrl, signOut } = useSession();
  return (
    <View style={[styles.screen, { backgroundColor: t.bg }]}>
      <Text style={{ color: t.text }}>Signed in as {user?.email ?? "…"}</Text>
      <Text style={{ color: t.muted }}>Server: {serverUrl}</Text>
      <Button title="Sign out" variant="plain" onPress={() => void signOut()} />
    </View>
  );
}

const styles = StyleSheet.create({ screen: { flex: 1, padding: 24, gap: 12 } });
