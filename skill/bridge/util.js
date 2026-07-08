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
