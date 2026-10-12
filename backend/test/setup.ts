import { env } from "cloudflare:test";
import { beforeAll } from "vitest";
import migrationSql from "../migrations/0001_init.sql?raw";

const MIGRATION_NAME = "0001_init.sql";

/**
 * Splits a migration file into individual statements. Done here (rather than
 * via `readD1Migrations`, which needs Node's filesystem) because this file runs
 * inside the Workers runtime, where `fs` and `wrangler` are unavailable.
 *
 * Understands single/double quoted strings, `--` line comments and `/* *​/`
 * block comments, so a semicolon inside any of them is not a boundary.
 */
function splitStatements(sql: string): string[] {
  const statements: string[] = [];
  let current = "";
  let inSingle = false;
  let inDouble = false;
  let inLineComment = false;
  let inBlockComment = false;

  for (let i = 0; i < sql.length; i++) {
    const ch = sql[i] as string;
    const next = sql[i + 1];

    if (inLineComment) {
      if (ch === "\n") inLineComment = false;
      current += ch;
      continue;
    }
    if (inBlockComment) {
      if (ch === "*" && next === "/") {
        inBlockComment = false;
        current += "*/";
        i++;
        continue;
      }
      current += ch;
      continue;
    }
    if (inSingle) {
      current += ch;
      if (ch === "'") {
        if (next === "'") {
          current += next;
          i++;
          continue;
        }
        inSingle = false;
      }
      continue;
    }
    if (inDouble) {
      current += ch;
      if (ch === '"') inDouble = false;
      continue;
    }
    if (ch === "-" && next === "-") {
      inLineComment = true;
      current += "--";
      i++;
      continue;
    }
    if (ch === "/" && next === "*") {
      inBlockComment = true;
      current += "/*";
      i++;
      continue;
    }
    if (ch === "'") {
      inSingle = true;
      current += ch;
      continue;
    }
    if (ch === '"') {
      inDouble = true;
      current += ch;
      continue;
    }
    if (ch === ";") {
      if (isExecutable(current)) statements.push(current.trim());
      current = "";
      continue;
    }
    current += ch;
  }

  if (isExecutable(current)) statements.push(current.trim());
  return statements;
}

/** Drops chunks that are only whitespace and comments. */
function isExecutable(statement: string): boolean {
  return statement
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/--[^\n]*/g, "")
    .trim().length > 0;
}

/**
 * Applied before every spec file so each suite runs against the real schema,
 * including the seeded challenges and achievements. Bookkeeping in
 * `d1_migrations` makes a repeat apply a no-op, so files can run in any order.
 */
beforeAll(async () => {
  const db = (env as unknown as { DB: D1Database }).DB;

  await db
    .prepare(
      `CREATE TABLE IF NOT EXISTS d1_migrations (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         name TEXT UNIQUE,
         applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
       )`,
    )
    .run();

  const already = await db.prepare(`SELECT name FROM d1_migrations WHERE name = ?1`).bind(MIGRATION_NAME).first();
  if (already) return;

  await db.batch(splitStatements(migrationSql).map((statement) => db.prepare(statement)));
  await db.prepare(`INSERT INTO d1_migrations (name) VALUES (?1)`).bind(MIGRATION_NAME).run();
});
