import { Pressable, StyleSheet, Text } from "react-native";
import { useTheme } from "./theme";

interface Props {
  title: string;
  onPress: () => void;
  disabled?: boolean;
  variant?: "primary" | "plain" | "danger";
}

export function Button({ title, onPress, disabled, variant = "primary" }: Props) {
  const t = useTheme();
  const background = variant === "primary" ? t.accent : "transparent";
  const color = variant === "primary" ? t.accentText : variant === "danger" ? t.error : t.text;
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={[styles.button, { backgroundColor: background, borderColor: variant === "primary" ? t.accent : t.border }, disabled && styles.disabled]}
    >
      <Text style={[styles.label, { color }]}>{title}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: { paddingVertical: 12, paddingHorizontal: 18, borderRadius: 10, borderWidth: 1, alignItems: "center" },
  label: { fontSize: 16, fontWeight: "600" },
  disabled: { opacity: 0.5 },
});
