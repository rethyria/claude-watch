// Machine-readable permission semantics (issue #10), in-process: the
// canonical option-builder contract and the Codex synthetic exec-approval
// menu, which cannot be driven black-box without a real Codex install
// writing ~/.codex session/log files. Black-box coverage of the Claude hook
// path lives in permission-semantics.test.js.
//
// Env overrides must be set before any bridge module loads (config.js and
// credentials.js read them at evaluation), hence the dynamic imports inside
// the tests. node --test runs each test file in its own process, so nothing
// leaks elsewhere.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-unit-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;
after(() => {
  try { fs.rmSync(credsDir, { recursive: true, force: true }); } catch { /* ignore */ }
});

function fakeRequest(headers = {}) {
  const req = new EventEmitter();
  req.method = "POST";
  req.headers = headers;
  return req;
}

function fakeResponse() {
  return {
    headersSent: false,
    statusCode: null,
    body: null,
    writeHead(status) {
      this.headersSent = true;
      this.statusCode = status;
    },
    end(payload, cb) {
      this.body = payload;
      if (cb) cb();
    },
  };
}

// A fake Codex session slot whose PTY stdin captures keystrokes.
function fakeCodexSession(sessions, id, writes) {
  sessions.set(id, {
    id,
    agent: "codex",
    cwd: "/tmp/codex-unit",
    folderName: "codex-unit",
    state: "running",
    createdAt: Date.now(),
    // Shaped like a live ChildProcess: writeToSessionStdin's liveness guard
    // checks writable/destroyed/exitCode before writing.
    ptyProcess: {
      stdin: { write: (data) => writes.push(data), writable: true, destroyed: false },
      exitCode: null,
    },
  });
}

test("canonical option builders tag every option with a machine-readable behavior", async () => {
  const { canonicalPermissionOptions, defaultPermissionOptions } = await import("../permissions.js");

  assert.deepEqual(
    defaultPermissionOptions({ canAllowAlways: true }).map((o) => o.behavior),
    ["allow", "allow-always", "deny"],
  );
  assert.deepEqual(
    defaultPermissionOptions().map((o) => o.behavior),
    ["allow", "deny"],
    "no allow-always option without suggestions to persist",
  );
  for (const option of defaultPermissionOptions({ canAllowAlways: true })) {
    assert.equal(typeof option.label, "string");
    assert.ok(option.label.length > 0);
  }

  // An option without a valid behavior must never be broadcast — a client
  // could only interpret it by guessing from position or wording.
  assert.throws(() => canonicalPermissionOptions([{ label: "Yes" }]), /machine-readable behavior/);
  assert.throws(
    () => canonicalPermissionOptions([{ behavior: "approve", label: "Yes" }]),
    /machine-readable behavior/,
  );
});

test("Codex synthetic menu is canonical; answers resolve by behavior, index, and label identically", async () => {
  const { buildCodexApprovalOptions, codexSyntheticPermissions, resolveCodexSyntheticPermission } =
    await import("../codex.js");
  const { sessions } = await import("../sessions.js");

  // The synthetic menu goes through the same canonical form as Claude prompts.
  const trustMenu = buildCodexApprovalOptions(["git", "push"]);
  assert.deepEqual(trustMenu.map((o) => o.behavior), ["allow", "allow-always", "deny"]);
  assert.match(trustMenu[1].description, /git push/);
  const plainMenu = buildCodexApprovalOptions([]);
  assert.deepEqual(plainMenu.map((o) => o.behavior), ["allow", "deny"]);

  const writes = [];
  const arm = (permissionId, options = trustMenu) => {
    fakeCodexSession(sessions, "codex-unit", writes);
    codexSyntheticPermissions.set(permissionId, {
      sessionId: "codex-unit",
      optionCount: options.length,
      payload: {
        permissionId,
        source: "codex",
        tool_name: "ExecApproval",
        options,
        tool_input: { command: "git push", workdir: "/tmp", questions: [{ options }] },
      },
    });
  };

  try {
    // Machine-readable behavior wins outright.
    arm("p-allow");
    assert.ok(resolveCodexSyntheticPermission("p-allow", undefined, undefined, "allow"));
    arm("p-always");
    assert.ok(resolveCodexSyntheticPermission("p-always", undefined, undefined, "allow-always"));
    arm("p-deny");
    assert.ok(resolveCodexSyntheticPermission("p-deny", undefined, undefined, "deny"));
    // Legacy index and label answers resolve against the SAME canonical list.
    arm("p-index");
    assert.ok(resolveCodexSyntheticPermission("p-index", undefined, 1));
    arm("p-label");
    assert.ok(resolveCodexSyntheticPermission("p-label", "Yes, proceed", undefined));
    // allow-always degrades to a single allow when the menu has no trust entry.
    arm("p-degrade", plainMenu);
    assert.ok(resolveCodexSyntheticPermission("p-degrade", undefined, undefined, "allow-always"));

    assert.deepEqual(
      writes,
      ["y", "2\n", "\u001b", "2\n", "y", "y"],
      "behavior, index, and label answers must map to identical keystrokes",
    );
  } finally {
    sessions.clear();
    codexSyntheticPermissions.clear();
  }
});

test("the decision endpoint routes behavior-based answers through to Codex synthetic permissions", async () => {
  const { handleCommand } = await import("../commands.js");
  const { issueToken } = await import("../credentials.js");
  const { buildCodexApprovalOptions, codexSyntheticPermissions } = await import("../codex.js");
  const { sessions } = await import("../sessions.js");

  const token = issueToken({ deviceName: "unit-watch", surface: "v1" });
  const options = buildCodexApprovalOptions(["npm"]);
  const writes = [];
  fakeCodexSession(sessions, "codex-unit", writes);
  codexSyntheticPermissions.set("cmd-perm", {
    sessionId: "codex-unit",
    optionCount: options.length,
    payload: {
      permissionId: "cmd-perm",
      source: "codex",
      tool_name: "ExecApproval",
      options,
      tool_input: { command: "npm install", workdir: "/tmp", questions: [{ options }] },
    },
  });

  try {
    const req = fakeRequest({ authorization: `Bearer ${token}` });
    const res = fakeResponse();
    const pending = handleCommand(req, res);
    req.emit("data", Buffer.from(JSON.stringify({
      permissionId: "cmd-perm",
      decision: { behavior: "allow-always" },
    })));
    req.emit("end");
    await pending;

    assert.equal(res.statusCode, 200);
    assert.deepEqual(JSON.parse(res.body), { ok: true });
    assert.deepEqual(writes, ["2\n"], "allow-always must select the Codex trust entry");
  } finally {
    sessions.clear();
    codexSyntheticPermissions.clear();
  }
});
