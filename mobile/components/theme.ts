import { useColorScheme } from "react-native";

const light = { bg: "#f6f7f9", surface: "#ffffff", text: "#1c1f24", muted: "#5f6877", border: "#d8dce3", accent: "#3b4cca", accentText: "#ffffff", error: "#b3261e", ok: "#1b7f3b" };
const dark = { bg: "#14161a", surface: "#1d2025", text: "#e8eaed", muted: "#9aa3b2", border: "#353a43", accent: "#7d8cff", accentText: "#11131a", error: "#ff8a80", ok: "#6bd68b" };

export type Theme = typeof light;

export function useTheme(): Theme {
  return useColorScheme() === "dark" ? dark : light;
}
