// Shared low-level helpers: logging and HTTP request/response plumbing.
// This module sits at the bottom of the dependency graph and must not import
// any other bridge module.

export function log(level, msg, ...args) {
  const ts = new Date().toISOString();
  const prefix = `[${ts}] [${level.toUpperCase()}]`;
  if (args.length) {
    console.log(prefix, msg, ...args);
  } else {
    console.log(prefix, msg);
  }
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
