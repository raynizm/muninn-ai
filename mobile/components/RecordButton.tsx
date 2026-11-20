import { Pressable, StyleSheet, Text, View } from "react-native";
import { useTheme } from "./theme";

interface Props {
  recording: boolean;
  disabled?: boolean;
  onPress: () => void;
}

/** Big round button: red dot to start, square to stop. */
export function RecordButton({ recording, disabled, onPress }: Props) {
  const t = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={recording ? "Stop recording" : "Start recording"}
      onPress={onPress}
      disabled={disabled}
      style={[styles.outer, { borderColor: t.border, opacity: disabled ? 0.5 : 1 }]}
    >
      <View style={[recording ? styles.stop : styles.dot, { backgroundColor: t.error }]} />
      <Text style={[styles.hidden]}>{recording ? "Stop" : "Record"}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  outer: { width: 96, height: 96, borderRadius: 48, borderWidth: 4, alignItems: "center", justifyContent: "center" },
  dot: { width: 64, height: 64, borderRadius: 32 },
  stop: { width: 36, height: 36, borderRadius: 6 },
  hidden: { position: "absolute", opacity: 0, width: 0, height: 0 },
});
