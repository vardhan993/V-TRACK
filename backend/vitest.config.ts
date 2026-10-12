import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

/**
 * Tests run *inside* the Workers runtime (workerd) via Miniflare, so D1,
 * Web Crypto and the fetch handler behave exactly as they do in production.
 * Nothing here reaches the network: bindings are local, migrations are applied
 * from ./migrations, and every test starts from an empty database.
 */
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      // Secrets normally provided with `wrangler secret put` are injected here so
      // the suite runs offline with no credentials on disk outside .git.
      miniflare: {
        bindings: {
          ENVIRONMENT: "test",
          JWT_SECRET: "test-only-jwt-secret-not-used-in-production",
          PASSWORD_PEPPER: "test-only-pepper-not-used-in-production",
          ADMIN_EMAILS: "admin@example.test",
          PBKDF2_ITERATIONS: "1000",
        },
      },
    }),
  ],
  test: {
    setupFiles: ["./test/setup.ts"],
    include: ["test/**/*.spec.ts"],
    testTimeout: 30_000,
    hookTimeout: 30_000,
    // Each file gets a fresh isolate; sharing state between files would leak
    // balances and users across specs.
    isolate: true,
  },
});
