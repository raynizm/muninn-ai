import { useState } from "react";
import { ActivityIndicator, KeyboardAvoidingView, Platform, StyleSheet, Text, TextInput, View } from "react-native";
import { Button } from "../components/Button";
import { useTheme } from "../components/theme";
import { useSession } from "../stores/session";

export default function Login() {
  const t = useTheme();
  const savedUrl = useSession((s) => s.serverUrl);
  const setServerUrl = useSession((s) => s.setServerUrl);
  const signIn = useSession((s) => s.signIn);
  const [url, setUrl] = useState(savedUrl ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await setServerUrl(url);
      await signIn();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Sign-in failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} style={[styles.screen, { backgroundColor: t.bg }]}>
      <View style={styles.card}>
        <Text style={[styles.title, { color: t.text }]}>Muninn</Text>
        <Text style={{ color: t.muted }}>A memory for everything you say. Enter the address of your own Muninn server.</Text>
        <TextInput
          value={url}
          onChangeText={setUrl}
          placeholder="muninn.example.com"
          placeholderTextColor={t.muted}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          style={[styles.input, { color: t.text, borderColor: t.border, backgroundColor: t.surface }]}
        />
        {error && <Text style={{ color: t.error }}>{error}</Text>}
        {busy ? <ActivityIndicator /> : <Button title="Sign in with SSO" onPress={() => void submit()} disabled={!url.trim()} />}
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, justifyContent: "center", padding: 24 },
  card: { gap: 16 },
  title: { fontSize: 32, fontWeight: "700" },
  input: { borderWidth: 1, borderRadius: 10, padding: 12, fontSize: 16 },
});
