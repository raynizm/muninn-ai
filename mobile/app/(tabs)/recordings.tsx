import { StyleSheet, Text, View } from "react-native";
import { useTheme } from "../../components/theme";

export default function Recordings() {
  const t = useTheme();
  return (
    <View style={[styles.screen, { backgroundColor: t.bg }]}>
      <Text style={{ color: t.muted }}>Your recordings will appear here.</Text>
    </View>
  );
}

const styles = StyleSheet.create({ screen: { flex: 1, padding: 24 } });
