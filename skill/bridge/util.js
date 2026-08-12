// Shared low-level helpers: logging and HTTP request/response plumbing.
// This module sits at the bottom of the dependency graph and must not import
// any other bridge module.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { format } from "node:util";

// Fallback log sink (issue #93): where log lines go once the primary sink is
// dead. Silencing a dead sink's errors (see the guards in server.js and the
// catch in log() below) keeps the bridge alive but made the state SILENT — a
// bridge started from a terminal that later closed served on forever with no
// log output at all, and the standing diagnostic doctrine here is to read the
// log rather than reason from the state machine. The path mirrors config.js's
// CREDENTIALS_DIR (including the CLAUDE_WATCH_CREDENTIALS_DIR override, so
// tests never touch the real one) rather than importing it: util.js must not
// import bridge modules, and config.js imports this one.
const FALLBACK_LOG_FILE = path.join(
  process.env.CLAUDE_WATCH_CREDENTIALS_DIR || path.join(os.homedir(), ".claude-watch"),
  "bridge.log",
);

let loggingDegraded = false;

// Surfaced as `loggingDegraded` in GET /v1/status, so the condition is
// visible from the admin surface and the wrist instead of inferred from
// silence.
export function isLoggingDegraded() {
  return loggingDegraded;
}

function appendToFallback(line) {
  try {
    fs.mkdirSync(path.dirname(FALLBACK_LOG_FILE), { recursive: true, mode: 0o700 });
    fs.appendFileSync(FALLBACK_LOG_FILE, `${line}\n`, { mode: 0o600 });
  } catch { /* both sinks gone: dropping the line is all that is left */ }
}

// Every primary-sink failure lands here — the asynchronous EPIPE the stream
// 'error' silencers in server.js observe, and the synchronous throw caught in
// log() below. One-way for the life of the process: a destroyed stdio stream
// never comes back, so neither does the flag.
export function logSinkFailed(err) {
  if (loggingDegraded) return;
  loggingDegraded = true;
  appendToFallback(format(
    `[${new Date().toISOString()}] [WARN]`,
    `Primary log sink failed (${err?.code || err?.message || err}); logging continues in this file`,
  ));
}

// A failed log write must never propagate: the process-level guards in
// server.js log the exceptions they catch, so a throwing log() turns one fault
// into an endless log→throw→log cycle. server.js silences the asynchronous
// EPIPE on stdout/stderr; the catch below handles the synchronous case (a
// destroyed or already-closed stream throws on write) for every caller.
export function log(level, msg, ...args) {
  const ts = new Date().toISOString();
  const prefix = `[${ts}] [${level.toUpperCase()}]`;
  try {
    if (args.length) {
      console.log(prefix, msg, ...args);
    } else {
      console.log(prefix, msg);
    }
  } catch (err) {
    logSinkFailed(err);
  }
  // Degraded: the line goes to the fallback file instead of being dropped —
  // including the very line whose write threw above. The console.log attempt
  // stays (a destroyed stream swallows it harmlessly), so a degradation on
  // one stream never costs a still-working sink its lines.
  if (loggingDegraded) appendToFallback(format(prefix, msg, ...args));
}

export function jsonResponse(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(payload),
  });
  res.end(payload);
}

// Loopback detection for socket remote addresses. Used to restrict the
// /hooks/* surface to callers on this machine: Claude Code hook scripts
// always POST from localhost, so any other source is a LAN peer trying to
// spoof permission prompts or terminal output onto the trusted watch UI.
// Handles plain IPv4 loopback (the whole 127.0.0.0/8 block), IPv6 loopback,
// and IPv4-mapped IPv6 addresses ("::ffff:127.0.0.1") as Node reports them.
export function isLoopbackAddress(addr) {
  if (typeof addr !== "string" || addr.length === 0) return false;
  let a = addr.toLowerCase();
  if (a.startsWith("::ffff:")) a = a.slice(7);
  if (a === "::1") return true;
  const m = a.match(/^127\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  return m !== null && m.slice(1).every((octet) => Number(octet) <= 255);
}

// Deterministic JSON for fingerprinting hook payloads (issue #63). Claude Code
// re-serializes hook bodies between the PreToolUse and PermissionRequest that
// describe the same tool call, and JSON key order is not contractual — plain
// JSON.stringify would hash two identical tool_inputs differently and silently
// lose the correlation. Object keys are sorted recursively; array order is
// preserved because it is semantic (argv, questions).
export function stableStringify(value) {
  if (value === null || typeof value !== "object") {
    // JSON.stringify returns undefined for undefined/function/symbol; a
    // fingerprint must always be a string, and "null" is the same thing
    // JSON.stringify writes for those inside an array.
    return JSON.stringify(value) ?? "null";
  }
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  const parts = [];
  for (const key of Object.keys(value).sort()) {
    // Mirror JSON.stringify: a key whose value is undefined is absent from
    // the output entirely, rather than serialized as null. Otherwise
    // {a: undefined} and {a: null} would fingerprint identically.
    if (value[key] === undefined) continue;
    parts.push(`${JSON.stringify(key)}:${stableStringify(value[key])}`);
  }
  return `{${parts.join(",")}}`;
}

// Maximum request body size. Bodies are buffered in memory before JSON.parse
// on unauthenticated endpoints (/pair, /hooks/*), so without a cap a single
// multi-GB POST OOMs the bridge before auth even runs. No legitimate client
// payload comes anywhere near 1 MiB. The constant lives here rather than in
// config.js because util.js sits at the bottom of the dependency graph and
// must not import bridge modules (config.js imports util.js).
export const MAX_REQUEST_BODY_BYTES = 1024 * 1024; // 1 MiB

// Answer 413 while the response is still writable, then destroy the request
// socket so the client stops streaming. Callers observe `err.tooLarge` on the
// rejection and must not write another response.
function respondTooLarge(req, res) {
  if (res && !res.headersSent) {
    try {
      const payload = JSON.stringify({ error: "Request body too large" });
      res.writeHead(413, {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(payload),
        Connection: "close",
      });
      // Destroy only after the 413 is flushed so the client has a chance to
      // read it before the reset.
      res.end(payload, () => req.destroy());
      return;
    } catch { /* socket already unusable */ }
  }
  req.destroy();
}

export function readBody(req, res = null) {
  return new Promise((resolve, reject) => {
    let done = false;
    const fail = (err) => {
      if (done) return;
      done = true;
      reject(err);
    };
    const tooLarge = () => {
      respondTooLarge(req, res);
      fail(Object.assign(new Error("Request body too large"), { tooLarge: true }));
    };

    // Reject an honestly-declared oversized body before buffering anything.
    const declared = Number(req.headers?.["content-length"]);
    if (Number.isFinite(declared) && declared > MAX_REQUEST_BODY_BYTES) {
      tooLarge();
      return;
    }

    const chunks = [];
    let received = 0;
    req.on("data", (c) => {
      if (done) return;
      received += c.length;
      if (received > MAX_REQUEST_BODY_BYTES) {
        tooLarge();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => {
      if (done) return;
      done = true;
      try {
        const raw = Buffer.concat(chunks).toString("utf-8");
        resolve(raw.length ? JSON.parse(raw) : {});
      } catch (err) {
        reject(err);
      }
    });
    req.on("error", fail);
  });
}
