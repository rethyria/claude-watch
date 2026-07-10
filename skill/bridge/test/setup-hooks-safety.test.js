// setup-hooks.sh safety: the installer must never damage anything it did not
// itself write. Ours are recognized by an EXACT match on loopback host + one
// of the installer's /hooks/<path> endpoints, filtered per hook OBJECT (never
// whole entries), so a user's own local-automation hooks survive install and
// --remove — even when they share an entry with ours. The settings.json
// rewrite is atomic (temp file + rename), so an interruption mid-write can
// never corrupt it, and all paths travel into the embedded Python via the
// environment, so a HOME containing quote characters works.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { tempDir, runScript, installedHookUrls } from "./helpers.js";

const SETUP_HOOKS = fileURLToPath(new URL("../../setup-hooks.sh", import.meta.url));

function settingsPath(home) {
  return path.join(home, ".claude", "settings.json");
}

function readSettings(home) {
  return JSON.parse(fs.readFileSync(settingsPath(home), "utf-8"));
}

function writeSettings(home, settings) {
  fs.mkdirSync(path.join(home, ".claude"), { recursive: true });
  fs.writeFileSync(settingsPath(home), JSON.stringify(settings, null, 2));
}

// Every scenario uses a high 4787x port argument: nothing listens there, the
// installer's /ping probe fails fast on loopback, and no parallel test bridge
// (range 7860-7929) can collide with the URLs under test.

test("user hooks at other 127.0.0.1 ports/paths survive install and --remove", { timeout: 60_000 }, async (t) => {
  const home = tempDir(t, "claude-watch-safety-home-");
  const userHooks = {
    // Different port AND a /hooks/ path that is not one of ours: the old
    // substring matcher (startswith 127.0.0.1 + contains /hooks/) nuked this.
    PostToolUse: [
      { hooks: [{ type: "http", url: "http://127.0.0.1:9455/hooks/my-automation", timeout: 5 }] },
    ],
    // Same port as the bridge URL, path outside /hooks/.
    Stop: [
      { hooks: [{ type: "http", url: "http://127.0.0.1:47872/webhook", timeout: 5 }] },
    ],
    // Non-HTTP hook with no url key at all.
    SessionStart: [
      { hooks: [{ type: "command", command: "echo session started" }] },
    ],
  };
  writeSettings(home, { model: "opus", hooks: userHooks });

  const install = await runScript(SETUP_HOOKS, ["47872"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(install.code, 0, install.output);

  let settings = readSettings(home);
  assert.equal(settings.model, "opus", "unrelated settings keys survive install");
  assert.deepEqual(settings.hooks.PostToolUse[0], userHooks.PostToolUse[0],
    "user hook on another port must survive install");
  assert.deepEqual(settings.hooks.Stop[0], userHooks.Stop[0],
    "user hook on the bridge port but another path must survive install");
  assert.deepEqual(settings.hooks.SessionStart, userHooks.SessionStart,
    "non-HTTP user hook must survive install");
  assert.ok(
    installedHookUrls(home).includes("http://127.0.0.1:47872/hooks/permission"),
    "claude-watch hooks must be installed alongside the user's",
  );

  const remove = await runScript(SETUP_HOOKS, ["--remove"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(remove.code, 0, remove.output);
  assert.ok(remove.output.includes("hooks removed"), remove.output);

  settings = readSettings(home);
  for (const url of installedHookUrls(home)) {
    assert.ok(
      !url.startsWith("http://127.0.0.1:47872/hooks/"),
      `claude-watch hook must be gone after --remove, found: ${url}`,
    );
  }
  assert.deepEqual(settings.hooks.PostToolUse, userHooks.PostToolUse,
    "user hook on another port must survive --remove");
  assert.deepEqual(settings.hooks.Stop, userHooks.Stop,
    "user hook on the bridge port but another path must survive --remove");
  assert.deepEqual(settings.hooks.SessionStart, userHooks.SessionStart,
    "non-HTTP user hook must survive --remove");
  assert.equal(settings.model, "opus");
});

test("mixed entry: only the claude-watch hook object is removed; user hooks in the same entry survive", { timeout: 60_000 }, async (t) => {
  const home = tempDir(t, "claude-watch-safety-home-");
  const claudeWatchHook = { type: "http", url: "http://127.0.0.1:47873/hooks/stop", timeout: 5 };
  const userCommandHook = { type: "command", command: "notify-send done" };
  // Exact matching, not prefix: one path segment past a real endpoint is not ours.
  const userHttpHook = { type: "http", url: "http://127.0.0.1:47873/hooks/stop/extra", timeout: 5 };
  const mixedEntry = { matcher: "Bash", hooks: [claudeWatchHook, userCommandHook, userHttpHook] };

  writeSettings(home, { hooks: { Stop: [structuredClone(mixedEntry)] } });
  const remove = await runScript(SETUP_HOOKS, ["--remove"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(remove.code, 0, remove.output);

  let entries = readSettings(home).hooks.Stop;
  assert.equal(entries.length, 1, "the mixed entry itself must survive --remove");
  assert.equal(entries[0].matcher, "Bash", "entry fields besides hooks are preserved");
  assert.deepEqual(entries[0].hooks, [userCommandHook, userHttpHook],
    "only the claude-watch hook object may be filtered out of the entry");

  // Install-time dedup shares the matcher: reinstalling over a mixed entry
  // must trim our object, keep the user's, and append fresh entries.
  writeSettings(home, { hooks: { Stop: [structuredClone(mixedEntry)] } });
  const install = await runScript(SETUP_HOOKS, ["47873"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(install.code, 0, install.output);

  entries = readSettings(home).hooks.Stop;
  const mixed = entries.find((e) => e.matcher === "Bash");
  assert.ok(mixed, "the mixed entry itself must survive install dedup");
  assert.deepEqual(mixed.hooks, [userCommandHook, userHttpHook],
    "install dedup must trim only the claude-watch hook object from the mixed entry");
  assert.ok(
    entries.some((e) => (e.hooks ?? []).some((h) => h.url === claudeWatchHook.url)),
    "a fresh claude-watch Stop hook entry must be appended",
  );
});

// Simulates a kill mid-write via a sitecustomize.py shim (loaded through
// PYTHONPATH by every python3 the installer spawns): the first write to any
// file opened for writing under the target directory flushes only half the
// data to disk, then hard-exits the process — exactly what a crash or SIGKILL
// during the settings rewrite does. With a direct open(settings, 'w') the
// file is left truncated; with temp file + rename it must be untouched.
const KILL_MID_WRITE_SHIM = `
import builtins, os

_target = os.environ.get("CLAUDE_WATCH_TEST_KILL_WRITES_UNDER")
if _target:
    _target = os.path.abspath(_target) + os.sep
    _real_open = builtins.open

    class _KillMidWrite:
        def __init__(self, fh):
            self._fh = fh

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            self._fh.close()

        def write(self, data):
            self._fh.write(data[: max(1, len(data) // 2)])
            self._fh.flush()
            os.fsync(self._fh.fileno())
            os._exit(9)

        def __getattr__(self, name):
            return getattr(self._fh, name)

    def _open(file, mode="r", *args, **kwargs):
        try:
            p = os.fspath(file)
        except TypeError:
            return _real_open(file, mode, *args, **kwargs)
        if isinstance(p, bytes):
            p = os.fsdecode(p)
        if "w" in mode and os.path.abspath(p).startswith(_target):
            return _KillMidWrite(_real_open(file, mode, *args, **kwargs))
        return _real_open(file, mode, *args, **kwargs)

    builtins.open = _open
`;

test("a write interrupted mid-flight cannot corrupt settings.json", { timeout: 60_000 }, async (t) => {
  const home = tempDir(t, "claude-watch-safety-home-");
  const original = {
    model: "opus",
    hooks: { PostToolUse: [{ hooks: [{ type: "command", command: "user-thing" }] }] },
  };
  writeSettings(home, original);
  const originalBytes = fs.readFileSync(settingsPath(home), "utf-8");

  const shimDir = tempDir(t, "claude-watch-killshim-");
  fs.writeFileSync(path.join(shimDir, "sitecustomize.py"), KILL_MID_WRITE_SHIM);

  const install = await runScript(SETUP_HOOKS, ["47874"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
    PYTHONPATH: shimDir,
    CLAUDE_WATCH_TEST_KILL_WRITES_UNDER: path.join(home, ".claude"),
  });
  assert.notEqual(install.code, 0,
    `the shim must have killed the installer mid-write, got exit 0: ${install.output}`);

  // The process died mid-write, yet settings.json is byte-identical: the
  // partial write landed in a temp file, never in the real settings.
  assert.equal(fs.readFileSync(settingsPath(home), "utf-8"), originalBytes,
    "an interrupted write must leave settings.json untouched");
  assert.deepEqual(readSettings(home), original);

  // And a subsequent normal run recovers cleanly.
  const retry = await runScript(SETUP_HOOKS, ["47874"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(retry.code, 0, retry.output);
  assert.ok(installedHookUrls(home).includes("http://127.0.0.1:47874/hooks/permission"));
});

test("a hardened settings.json keeps its permissions across install and --remove", { timeout: 60_000 }, async (t) => {
  // settings.json can carry secrets (env vars, apiKeyHelper config); a naive
  // temp-file-and-rename rewrite would silently replace a user's chmod 600
  // with the temp file's umask-default 0644.
  const home = tempDir(t, "claude-watch-safety-home-");
  writeSettings(home, { env: { SECRET_TOKEN: "hunter2" } });
  fs.chmodSync(settingsPath(home), 0o600);

  const install = await runScript(SETUP_HOOKS, ["47876"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(install.code, 0, install.output);
  let mode = fs.statSync(settingsPath(home)).mode & 0o777;
  assert.equal(mode, 0o600,
    `install must preserve settings.json permissions, got 0${mode.toString(8)}`);
  assert.ok(installedHookUrls(home).includes("http://127.0.0.1:47876/hooks/permission"));

  const remove = await runScript(SETUP_HOOKS, ["--remove"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(remove.code, 0, remove.output);
  mode = fs.statSync(settingsPath(home)).mode & 0o777;
  assert.equal(mode, 0o600,
    `--remove must preserve settings.json permissions, got 0${mode.toString(8)}`);
  assert.equal(readSettings(home).env.SECRET_TOKEN, "hunter2",
    "settings content must survive alongside its permissions");
});

test("install and --remove work from a HOME containing a quote character", { timeout: 60_000 }, async (t) => {
  const base = tempDir(t, "claude-watch-safety-quote-");
  const home = path.join(base, "it's home");
  fs.mkdirSync(home);

  const install = await runScript(SETUP_HOOKS, ["47875"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(install.code, 0, install.output);
  assert.ok(install.output.includes("Hooks installed successfully"), install.output);
  const urls = installedHookUrls(home);
  assert.ok(
    urls.includes("http://127.0.0.1:47875/hooks/permission"),
    `hooks must be written from a quoted HOME, got: ${urls.join(", ")}`,
  );

  const remove = await runScript(SETUP_HOOKS, ["--remove"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(remove.code, 0, remove.output);
  assert.equal(readSettings(home).hooks, undefined,
    "all claude-watch hooks must be removed from a quoted HOME");
});
