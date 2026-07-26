/**
 * The learned-context-window cache is persisted so it survives the adapter
 * process, which dies with the editor. Without persistence every editor
 * restart re-armed the DEFAULT_CONTEXT_WINDOW heuristic for models whose
 * id/displayName/description carry no "1m" token (e.g. `sonnet` →
 * claude-sonnet-5, natively 1M), and the first turn after each restart
 * streamed `usage_update.size: 200000` until its `result.modelUsage` landed.
 */
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { __contextWindowCacheTestHooks as hooks, contextWindowCachePath } from "../acp-agent.js";

const PROVIDER = "test-provider-key";
/** A model whose every identity string is free of a "1m" token — the case the
 *  text heuristic cannot catch, and therefore the only one persistence
 *  actually rescues. */
const MODEL = "claude-sonnet-5";

let cacheHome: string;
let previousCacheHome: string | undefined;

beforeEach(() => {
  previousCacheHome = process.env.XDG_CACHE_HOME;
  cacheHome = mkdtempSync(path.join(tmpdir(), "acp-window-cache-"));
  process.env.XDG_CACHE_HOME = cacheHome;
  // The production writer creates this itself; tests that pre-seed the file by
  // hand need it to already exist.
  mkdirSync(path.dirname(contextWindowCachePath()), { recursive: true });
  hooks.reset();
});

afterEach(() => {
  if (previousCacheHome === undefined) delete process.env.XDG_CACHE_HOME;
  else process.env.XDG_CACHE_HOME = previousCacheHome;
  rmSync(cacheHome, { recursive: true, force: true });
  hooks.reset();
});

/** Simulate the adapter process restarting: in-memory state gone, disk kept. */
function restartAdapter(): void {
  hooks.reset();
}

describe("persisted context-window cache", () => {
  it("seeds a fresh process from a window learned before the restart", () => {
    // Nothing on disk yet: the heuristic misses this model and falls back.
    expect(hooks.seed(PROVIDER, MODEL)).toEqual({ size: 200000, authoritative: false });

    // A turn confirms the real window via result.modelUsage.
    hooks.learn(hooks.key(PROVIDER, MODEL), 1_000_000);

    restartAdapter();

    // The whole point: no 200k window on the first turn after a restart.
    expect(hooks.seed(PROVIDER, MODEL)).toEqual({ size: 1_000_000, authoritative: true });
  });

  it("scopes persisted windows per backend", () => {
    hooks.learn(hooks.key(PROVIDER, MODEL), 1_000_000);
    restartAdapter();

    // Extended context is entitlement-gated per credential/endpoint, so a
    // window learned on one backend must never seed a session on another.
    expect(hooks.seed("a-different-backend", MODEL)).toEqual({
      size: 200000,
      authoritative: false,
    });
  });

  it("never writes provider credentials to disk", () => {
    // The provider cache key positionally joins ANTHROPIC_API_KEY,
    // ANTHROPIC_AUTH_TOKEN and ANTHROPIC_CUSTOM_HEADERS among others. The
    // in-memory Map key used to hold them verbatim; persisting that would put
    // live credentials in a plaintext file.
    const secret = "sk-ant-super-secret-value";
    hooks.learn(hooks.key(`\0\0${secret}\0`, MODEL), 1_000_000);

    const raw = readFileSync(contextWindowCachePath(), "utf8");
    expect(raw).not.toContain(secret);
    // The model id is not a secret and is still stored plainly, so the entry
    // remains debuggable.
    expect(raw).toContain(MODEL);
  });

  it("survives a corrupt or truncated cache file", () => {
    writeFileSync(contextWindowCachePath(), "{not json");
    restartAdapter();

    // Degrades to the heuristic rather than throwing out of the seed path,
    // which sits on the session/new critical path.
    expect(() => hooks.seed(PROVIDER, MODEL)).not.toThrow();
    expect(hooks.seed(PROVIDER, MODEL).authoritative).toBe(false);
  });

  it("ignores entries from a future cache format", () => {
    writeFileSync(
      contextWindowCachePath(),
      JSON.stringify({ version: 999, entries: [["whatever", MODEL, 1_000_000]] }),
    );
    restartAdapter();

    expect(hooks.seed(PROVIDER, MODEL).authoritative).toBe(false);
  });

  it("rejects non-positive windows from disk", () => {
    // Third-party backends have been observed reporting nonsensical windows;
    // the in-memory writer guards on `> 0` and the loader must agree, or a
    // bad value would outlive the process that saw it.
    const [providerHash] = hooks.key(PROVIDER, MODEL).split("\0");
    writeFileSync(
      contextWindowCachePath(),
      JSON.stringify({
        version: 1,
        entries: [
          [providerHash, MODEL, 0],
          [providerHash, "other-model", -5],
        ],
      }),
    );
    restartAdapter();

    expect(hooks.seed(PROVIDER, MODEL).authoritative).toBe(false);
    expect(hooks.seed(PROVIDER, "other-model").authoritative).toBe(false);
  });

  it("drops the persisted file on logout", () => {
    hooks.learn(hooks.key(PROVIDER, MODEL), 1_000_000);
    expect(existsSync(contextWindowCachePath())).toBe(true);

    // 1M entitlement differs per account/tier, and an OAuth re-login is
    // invisible to the env-derived provider key — so the on-disk copy has to
    // go with the in-memory one.
    hooks.clearPersisted();
    expect(existsSync(contextWindowCachePath())).toBe(false);

    restartAdapter();
    expect(hooks.seed(PROVIDER, MODEL).authoritative).toBe(false);
  });
});
