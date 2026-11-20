import type { File } from "expo-file-system";

/** Hex SHA-256 of a file, computed natively without loading it into JavaScript memory. */
export function sha256OfFile(file: File): Promise<string> {
  return file.digest("SHA-256");
}
