import { Tabs } from "expo-router";
import { useTheme } from "../../components/theme";

export default function TabsLayout() {
  const t = useTheme();
  return (
    <Tabs screenOptions={{ tabBarActiveTintColor: t.accent, headerStyle: { backgroundColor: t.surface }, headerTintColor: t.text, tabBarStyle: { backgroundColor: t.surface } }}>
      <Tabs.Screen name="index" options={{ title: "Home" }} />
      <Tabs.Screen name="recordings" options={{ title: "Recordings" }} />
      <Tabs.Screen name="settings" options={{ title: "Settings" }} />
    </Tabs>
  );
}
