import Database from "better-sqlite3";
import type { SqlDb } from "../services/db";

/** SQLite for tests, with the same async surface as expo-sqlite's SQLiteDatabase. */
export function openTestDb(file = ":memory:"): SqlDb & { close(): void } {
  const db = new Database(file);
  return {
    async execAsync(sql) {
      db.exec(sql);
    },
    async runAsync(sql, ...params) {
      return { changes: db.prepare(sql).run(...params).changes };
    },
    async getAllAsync<T>(sql: string, ...params: unknown[]) {
      return db.prepare(sql).all(...params) as T[];
    },
    async getFirstAsync<T>(sql: string, ...params: unknown[]) {
      return (db.prepare(sql).get(...params) as T | undefined) ?? null;
    },
    close() {
      db.close();
    },
  };
}
