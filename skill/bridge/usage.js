// GET /v1/usage — on-demand plan-limit fetch (issue #57).
//
// The watch's usage page renders one remaining-usage bar per plan window
// (5-hour session, weekly all-models, weekly model-scoped). The data lives
// behind Anthropic's OAuth usage endpoint, whose bearer token is Claude
// Code's own access token (~/.claude/.credentials.json) — a secret that must
// NEVER leave this machine. The bridge therefore proxies: the handler reads
// the token, calls the upstream API, and hands the watch only the normalized
// bars. Strictly on demand — the client fetches on page open only, so there
// is no polling, no timer, and no bridge-side caching machinery here.
//
// Fallback: when the API is unreachable (offline, or the token expired while
// Claude Code isn't running to refresh it), Claude Code's own cached snapshot
// in ~/.claude.json (`cachedUsageUtilization`) has the same limits shape; it
// is served with `source: "cache"` plus its `fetchedAtMs` so the client can
// render "as of Xm ago". Neither available → 503.
//
// SECURITY INVARIANT: the OAuth access token appears in the upstream request
// header and nowhere else — never in a response body, never in a log line
// (error strings below are built from status codes and error names, not from
// anything that could embed the header).
import fs from "node:fs";
import { log, jsonResponse } from "./util.js";
import { requireAuth } from "./credentials.js";
import {
  USAGE_API_URL,
  USAGE_CREDENTIALS_PATH,
  USAGE_CACHE_PATH,
  USAGE_FETCH_TIMEOUT_MS,
} from "./config.js";

// Normalize an upstream `limits` array to the wire shape (PROTOCOL.md
// "Usage"). Render-what-you-get: every well-formed upstream entry survives,
// in upstream order, so a plan with different/extra windows still renders one
// bar each. Only the display label is folded in bridge-side:
//   session       → "5-hour"
//   weekly_all    → "weekly"
//   weekly_scoped → the scoped model's display name (e.g. "Fable"),
//                   falling back to the kind when absent
//   anything else → its kind verbatim
// `percent` is USED percent exactly as upstream reports (rounded to an
// integer); `resetsAt` is the upstream `resets_at` ISO timestamp verbatim.
// `severity` is the upstream's OWN color coding (observed value: "normal"),
// passed through verbatim when it is a non-empty string and OMITTED
// otherwise — its exact thresholds are undocumented, so the bridge never
// interprets it; clients treat it as the authoritative tier when present and
// non-"normal" (PROTOCOL.md "Usage").
// Returns null when the array yields no usable entries, so callers treat the
// source as unavailable rather than serving an empty bar list.
function normalizeLimits(rawLimits) {
  if (!Array.isArray(rawLimits)) return null;
  const limits = [];
  for (const entry of rawLimits) {
    if (!entry || typeof entry !== "object" || typeof entry.kind !== "string") continue;
    const { kind } = entry;
    let label;
    if (kind === "session") label = "5-hour";
    else if (kind === "weekly_all") label = "weekly";
    else if (kind === "weekly_scoped") label = entry.scope?.model?.display_name || kind;
    else label = kind;
    limits.push({
      kind,
      label,
      percent: Math.round(Number(entry.percent) || 0),
      resetsAt: typeof entry.resets_at === "string" ? entry.resets_at : null,
      // Upstream-verbatim, key omitted entirely when absent/empty: the
      // client's "server tier wins" logic keys on the field's PRESENCE.
      ...(typeof entry.severity === "string" && entry.severity.length > 0
        ? { severity: entry.severity }
        : {}),
    });
  }
  return limits.length > 0 ? limits : null;
}

// Read Claude Code's OAuth access token. Missing/unreadable/corrupt files
// all resolve to null (the caller falls through to the cache); the content is
// a secret, so no failure detail beyond "unavailable" is ever surfaced.
function readAccessToken() {
  try {
    const creds = JSON.parse(fs.readFileSync(USAGE_CREDENTIALS_PATH, "utf-8"));
    const token = creds?.claudeAiOauth?.accessToken;
    return typeof token === "string" && token.length > 0 ? token : null;
  } catch {
    return null;
  }
}

// One bounded upstream fetch. Returns the normalized limits array or throws
// a sanitized Error (safe to log/serve — built only from the HTTP status or
// the failure class, never from request headers).
async function fetchUpstreamLimits(token) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), USAGE_FETCH_TIMEOUT_MS);
  timer.unref();
  // The timer must stay armed across BOTH awaits: the headers phase (fetch)
  // and the body read (json). An upstream that answers 200 and then stalls
  // mid-body would otherwise hang this handler unbounded — the signal is
  // attached to the fetch, so aborting also cancels an in-flight body read.
  let body;
  try {
    let upstream;
    try {
      upstream = await fetch(`${USAGE_API_URL}/api/oauth/usage`, {
        headers: {
          Authorization: `Bearer ${token}`,
          "anthropic-beta": "oauth-2025-04-20",
        },
        signal: controller.signal,
      });
    } catch (err) {
      throw new Error(
        err?.name === "AbortError"
          ? `usage API timed out after ${USAGE_FETCH_TIMEOUT_MS}ms`
          : "usage API unreachable",
      );
    }
    if (!upstream.ok) {
      throw new Error(`usage API answered ${upstream.status}`);
    }
    try {
      body = await upstream.json();
    } catch (err) {
      // A timeout mid-body surfaces here (checked via signal.aborted — undici
      // versions differ on the error it rejects the body read with).
      throw new Error(
        err?.name === "AbortError" || controller.signal.aborted
          ? `usage API timed out after ${USAGE_FETCH_TIMEOUT_MS}ms`
          : "usage API returned non-JSON",
      );
    }
  } finally {
    clearTimeout(timer);
  }
  const limits = normalizeLimits(body?.limits);
  if (!limits) {
    throw new Error("usage API returned no recognizable limits");
  }
  return limits;
}

// Cache fallback: Claude Code's own snapshot in ~/.claude.json. Returns the
// {limits, fetchedAtMs} pair or null when the file/shape is unusable.
function readCachedLimits() {
  try {
    const cached = JSON.parse(fs.readFileSync(USAGE_CACHE_PATH, "utf-8"))?.cachedUsageUtilization;
    const limits = normalizeLimits(cached?.utilization?.limits);
    if (!limits) return null;
    return {
      limits,
      fetchedAtMs: Number.isFinite(cached.fetchedAtMs) ? cached.fetchedAtMs : undefined,
    };
  } catch {
    return null;
  }
}

export async function handleUsage(req, res) {
  if (req.method !== "GET") {
    return jsonResponse(res, 405, { error: "Method not allowed" });
  }
  // Plan-limit percentages are account state; like /status, nothing here is
  // readable by unauthenticated LAN peers.
  if (!requireAuth(req)) {
    return jsonResponse(res, 401, { error: "Unauthorized" });
  }

  // (a)+(b): live API, when a token is readable.
  let apiFailure = "no readable OAuth credentials";
  const token = readAccessToken();
  if (token) {
    try {
      const limits = await fetchUpstreamLimits(token);
      return jsonResponse(res, 200, { limits, source: "api" });
    } catch (err) {
      apiFailure = err.message; // sanitized by construction (see above)
    }
  }

  // (d): cache fallback. fetchedAtMs is present ONLY on this source — the
  // client renders it as a staleness line.
  const cached = readCachedLimits();
  if (cached) {
    log("info", `Serving usage from cache (${apiFailure})`);
    return jsonResponse(res, 200, {
      limits: cached.limits,
      source: "cache",
      ...(cached.fetchedAtMs !== undefined ? { fetchedAtMs: cached.fetchedAtMs } : {}),
    });
  }

  // (e): neither source yielded data.
  log("warn", `Usage unavailable: ${apiFailure}; no cached utilization`);
  return jsonResponse(res, 503, {
    error: `usage unavailable: ${apiFailure} and no cached utilization`,
  });
}
