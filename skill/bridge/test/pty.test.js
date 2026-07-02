import { test } from "node:test";
import assert from "node:assert/strict";
import { buildPtySpawnArgs, shellQuote, spawnPtyProcess } from "../pty.js";

const isLinux = process.platform === "linux";

function collect(proc) {
  const state = { out: "" };
  proc.stdout.on("data", (d) => { state.out += d.toString(); });
  proc.stderr.on("data", (d) => { state.out += d.toString(); });
  return state;
}

async function waitFor(predicate, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return true;
    await new Promise((r) => setTimeout(r, 25));
  }
  return predicate();
}

test("linux uses util-linux -qec syntax with winsize and a quoted command string", () => {
  assert.deepEqual(
    buildPtySpawnArgs("/usr/bin/claude", ["resume", "abc"], { platform: "linux", cols: 120, rows: 40 }),
    ["-qec", "stty cols 120 rows 40 2>/dev/null; exec '/usr/bin/claude' 'resume' 'abc'", "/dev/null"],
  );
});

test("linux restores the user's SHELL for the agent when provided", () => {
  const [, cmd] = buildPtySpawnArgs("/usr/bin/claude", [], { platform: "linux", cols: 80, rows: 24, userShell: "/usr/bin/fish" });
  assert.match(cmd, /SHELL='\/usr\/bin\/fish' exec '\/usr\/bin\/claude'$/);
});

test("darwin keeps BSD argument-vector syntax", () => {
  assert.deepEqual(
    buildPtySpawnArgs("/usr/bin/claude", ["resume", "abc"], { platform: "darwin" }),
    ["-q", "/dev/null", "/usr/bin/claude", "resume", "abc"],
  );
});

test("shellQuote survives spaces and single quotes", () => {
  assert.equal(
    shellQuote(["/bin/echo", "it's a test", "a b"]),
    `'/bin/echo' 'it'\\''s a test' 'a b'`,
  );
});

test("spawned process runs and emits output on this platform", async () => {
  const proc = spawnPtyProcess("/bin/echo", ["hello-from-pty"], {
    cwd: process.cwd(),
    env: process.env,
  });
  const state = collect(proc);
  const exitCode = await new Promise((resolve) => proc.on("close", resolve));
  assert.equal(exitCode, 0);
  assert.match(state.out, /hello-from-pty/);
});

test("shell metacharacters in args arrive literally (no injection)", async () => {
  const payload = ["$(touch /tmp/pty-pwned)", "a;b", "`id`", "x && y"];
  const proc = spawnPtyProcess("/bin/echo", payload, {
    cwd: process.cwd(),
    env: process.env,
  });
  const state = collect(proc);
  const exitCode = await new Promise((resolve) => proc.on("close", resolve));
  assert.equal(exitCode, 0);
  assert.ok(state.out.includes("$(touch /tmp/pty-pwned)"), `payload mangled: ${state.out}`);
  assert.ok(state.out.includes("a;b"));
});

test("spawned process stays alive and is interactive through the wrapper", async () => {
  const proc = spawnPtyProcess("/bin/cat", [], {
    cwd: process.cwd(),
    env: process.env,
  });
  const state = collect(proc);
  proc.stdin.write("ping\n");
  assert.ok(await waitFor(() => /ping/.test(state.out)), "echo not seen");
  assert.equal(proc.exitCode, null, "process should still be running");
  proc.kill();
  await new Promise((resolve) => proc.on("close", resolve));
});

test("PTY winsize is set via stty on linux", { skip: !isLinux }, async () => {
  const proc = spawnPtyProcess("/bin/sh", ["-c", "stty size"], {
    cwd: process.cwd(),
    env: process.env,
    cols: 120,
    rows: 40,
  });
  const state = collect(proc);
  await new Promise((resolve) => proc.on("close", resolve));
  assert.match(state.out, /40 120/);
});

test("agent sees the user's SHELL, not the /bin/sh wrapper pin", { skip: !isLinux }, async () => {
  const proc = spawnPtyProcess("/bin/sh", ["-c", "echo MY_SHELL=$SHELL"], {
    cwd: process.cwd(),
    env: { ...process.env, SHELL: "/usr/bin/fish" },
  });
  const state = collect(proc);
  await new Promise((resolve) => proc.on("close", resolve));
  assert.match(state.out, /MY_SHELL=\/usr\/bin\/fish/);
});
