# Handoff: Claude Watch — "Halo" Wear OS app

_Imported from the claude.ai/design project "Claude Watch UI/UX concepts"
(design_handoff_claude_watch_halo/README.md). This is the canonical spec for
the Halo implementation; the design tokens live in code at
`app/.../ui/halo/HaloTheme.kt` and the state derivation at `HaloModel.kt`._

## Overview
Halo is the chosen UI direction for **Claude Watch**, a Wear OS companion that
mirrors AI coding-agent sessions (Claude Code) from a laptop "bridge" to the
wrist. It is glance-first: the round screen is a status ring, the center is the
time, and everything the agent needs from the user (permission approvals,
questions) is at most one tap or swipe away. The agent **blocks** until
permissions are answered, so the approval flow is the highest-stakes surface.

Recreate this in **Compose for Wear OS** using platform components
(`ScalingLazyColumn`, `HorizontalPager`, `TimeText`, `Chip`, `Vignette`, rotary
input, ambient support) — do not port HTML. Wire to the existing wave-2
`BridgeViewModel` (actions: pair, unpair, sendCommand, updateCommandDraft,
dictationResult, answerPermission, answerQuestions, dismissPermissionLocally,
spawnSession, killSession) and render `HaloModel.from(uiState)`.

## Information Architecture
- **Horizontal pages (pager + dots): All → project 1 → project 2 → …** Page
  indicator: bottom-center dots, current = 11px `#F4F1EA`, others = 8px
  `#4A4C52`; dots tappable.
- **Vertical = depth:** swipe up on All → all-sessions list (grouped by project
  dividers); swipe up on a project → that project's session list; tap a session
  row → live session feed; swipe down steps back (session → list → page).
- Session states: `waiting-perm`, `waiting-q`, `running`, `idle`, `error`.

## Design Tokens (mirrored in HaloTheme.kt)
Colors (AMOLED-first): bg `#000000`, surface `#191B20`, surface-2 `#23262D`,
inset well `#16181D`; text primary `#F4F1EA`, secondary `#8D8B84`, faint
`#63615B`. Semantic: waiting-for-you `#D97757` (terracotta), running `#6CB289`,
idle `#3A3C42`, error/offline `#E5484D`. Ambient dimmed: terracotta `#7A4634`,
neutral `#222329`. Approve text on terracotta: `#1A0F0A`. User entries `#E8A889`.
Page dots current `#F4F1EA` / others `#4A4C52`.

Type (Roboto; Roboto Mono for commands/tool lines): centerpiece time 88/light;
big count 100/bold; screen title 24–26/medium; body 24–25; caption/meta 20–22;
mono command 26/medium; **minimum 20**. Radii: pills fully rounded; cards/wells
16–18px; rows 26px. Touch targets ≥48dp; content respects a ~56px circular
safe-area inset (never let text reach the curve — the core failure of the
previous build). All px at a 450×450 reference; use dp proportionally.

## Screens

### 1. All view (home, page 0)
Ring: one arc segment **per session** (all sessions), stroke 9, round caps,
colored by state; equal segments with small gaps. The ring is placed by its
OUTER edge, 6px in from the display edge (≈3dp — what first-party Wear edge
chrome hugs to), so the rim line does not move when the ambient stroke thins
to 4. (Superseded the original bare "205px radius", which left ~7.8dp of dead
rim and collided with the page dots at 3+ pages; the ~56px safe inset above is
a text rule and never governed the ring.) Center: time (88
light) + subtitle "3 projects · 5 sessions" (22, `#8D8B84`). Bottom: page dots
(y≈414). Tap center → opens the first waiting item directly. No top `TimeText`
on pages showing the centerpiece time; inner screens show top `TimeText` (20,
`#7E7C76`); it is purely decorative — swipe-down steps back up the depth
stack (the clock is deliberately not a tap target: an invisible hotspot over
the time read as an accidental-jump trap in live testing).

### 2. Project page (pages 1..n)
Ring: that project's sessions only (1 session ⇒ near-full ring). Center: time +
project name (24, `#8D8B84`), no counts. "↑ sessions" hint (20, faint) above
dots; tap center → project's first waiting item.

### 3. Session list (swipe up)
Title "all sessions" or "{project} · sessions" (22). All-sessions variant groups
rows with **project dividers** (label 19/medium `#63615B` + 1px rule `#26282E`).
Row: 26px-radius pill `#191B20`; 12px state dot; title 24/medium **wrapping**;
status subtitle 20 (`#63615B`, or `#D97757` when waiting). Waiting rows: bg
`rgba(217,119,87,.12)` + 2px `#D97757` border. **Row quick actions:** horizontal
swipe toggles an action strip — 50px circular buttons `#23262D`, 17px labels:
mode ◐ · compact ▤ · handover ⇄ · close ✕ (close: red tint / `#E5484D`, ends the
session → idle). Mode/compact/handover are stubs. List scrolls via rotary.

### 4. Session feed
Header: ‹ › cycle sessions within the project (also horizontal swipe); state dot
+ wrapping title (24/1.15, max 230); meta "1 of 2 · {project}" (20, faint). Feed
(bottom-anchored, last ~6 entries, rotary): tool calls 23 mono `#63615B`;
results 23 mono `#8D8B84` (pass counts green); agent prose 25 Roboto `#F4F1EA`;
user entries 24 medium `#E8A889`. If waiting: persistent bottom banner (terracotta
gradient), "waiting for permission →" / "has a question →" (23 medium `#D97757`);
tap opens the card. Otherwise a "Dictate" pill (`#23262D`) at bottom.

### 5. Approval card (highest stakes)
Header: `PERMISSION` (22 semibold `#D97757` letterspaced) + "2 waiting" when
queue > 1. Identity block (pill `#191B20`): dot + project (20, `#8D8B84`);
session title (23 medium, wraps). Command well: full-width `#16181D`, 1px
`#35281F`, 16px radius, command centered 26 mono `#F4F1EA`; sub-line "Bash · agent
is blocked" (22, `#8D8B84`). Buttons: Deny (outline 2px `#3A3C42`, text `#B9B7AF`)
and Approve (filled `#D97757`, text `#1A0F0A`), 76px pills, Approve wider. Both
single tap — **add a ~400ms debounce ignoring taps right after the card appears**.
"Always allow … ›" small faint below. "decide later ↓" exits without answering.
Result flash 1.4s: green ✓ "Approved · sent to bridge · agent resumed" or grey ✕
"Denied · agent notified". **Queue chaining: resolving a card slides in the next
waiting item from the right; only an empty queue returns home.**

### 6. Question card (AskUserQuestion)
Header stack (narrow top of circle): "question 1 of 2" (20, `#D97757`) → session
title (22, `#8D8B84`, wraps, max 260) → question (28 medium `#F4F1EA`). Options:
full-width pills 62px min, `#191B20`, text 24; selection advances to the next
question; answers **buffered and submitted together** after the last. "Dictate an
answer…" is always the last option. "answer later ↓" exits, losing nothing. Same
flash + queue chaining as approvals.

### 7. Voice command (ack-gated)
Listening: concentric terracotta circles (150/104/64), live transcript (26,
`#F4F1EA`), target session named ("to {session} · tap to send"). Sending:
transcript in a well + "sending… waiting for ack" (`#E8A889`) + Cancel. **Never
shown as sent until the bridge ACKs.** Success: feed entry "you: … ✓". Failure
(no ack in ~3s): red-dashed transcript, "not delivered — bridge didn't ack",
Retry / Discard. (Existing ViewModel already enforces ack-gating.)

> **Implementation deviation (accepted):** the LISTENING phase is the system
> recognizer activity (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`), not a
> custom screen — Wear's recognizer intent offers no partial-result stream
> for a live transcript, covers the whole display, and auto-submits on
> end-of-speech, so the concentric circles, the styled live transcript, and
> the tap-to-send affordance are not implemented. The target-naming intent
> survives as the recognizer's prompt line ("To {session}"), set at launch
> from the summoning surface's session. Everything AFTER transcription —
> sending hold, ack gating, failure with Retry/Discard — follows this spec
> verbatim (`HaloVoiceScreen.kt`, overlay lifecycle in `HaloApp.kt`). The
> failed state is modal (Retry/Discard are the only exits) and Cancel during
> sending keeps the overlay armed so an eventual failure reopens it: no other
> Halo surface renders the restored draft, and the text must never be lost
> silently.

### 8. Offline / re-pair
Ring hollow grey (same geometry, drained). "Bridge offline" (30, `#E5484D`),
"reconnecting… retry in Ns" countdown, "Re-pair watch" outline chip (terracotta).
Pairing screen: spinner ring + "pairing… looking for bridge on LAN". Withhold
pending approvals while offline.

### 9. Ambient (always-on)
Same layout as All: ring stroke 4 — waiting `#7A4634`, others `#222329`; time
centered 88/light `#8D8B84`. No fills/washes, minimal lit pixels. Wake restores
the full screen in place.

## Interactions & Motion
Page/screen transitions: 300ms cubic-bezier(0.2,0.7,0.3,1), 70px directional
slide + fade from swipe direction; non-spatial jumps (post-flash) fast
fade. Row action reveal 250ms/46px. Swipe threshold ≈60px (row-action ≈40px);
suppress the synthetic tap after a swipe (~300ms guard). Rotary scrolls
lists/feeds. Live feed streams; keep visible tail (~6 lines), bottom-anchored.

## Data-mapping notes (bridge → Halo)
- "Projects" are derived from `SessionState.folderName` (fallback: cwd basename);
  the bridge has no project entity. See `HaloModel.from`.
- Session `state` is derived: a queued permission → waiting-perm; a queued
  AskUserQuestion → waiting-q; else `activity==WORKING`/thinking → running;
  else `agents.running > 0` → **delegated**; else idle. Per-session `error` is
  not modeled yet (offline is connection-level).
- **Delegated (blue, `#6BA8D8`)** is the main loop having yielded while its
  workflow subagents keep running. "The turn ended" and "nothing is happening"
  are different claims, and this is where they diverge: green would promise an
  agent that will answer you, grey would deny work that is genuinely in
  flight — so the long unattended stretch a workflow occupies gets its own
  reading. It sits BELOW running (a churning main loop outranks its fleet) and
  below both waiting states (needing you always wins), and it clears the
  moment the agent count reaches zero. The colour is luminance-matched to
  Running (8.1:1 vs 8.2:1 on black) so the ring reads as a peer state rather
  than an alarm. Note this fixes the LIVE path too, not just reconnect: before
  it, `agents` was ignored by the derivation entirely, so a session went grey
  the instant its turn ended however large the fleet it had just launched.
- **Idle is now a first-class bridge-side state** (issue #60): session events
  carry an optional `idle: true` once the session's last lifecycle signal was a
  turn end. The watch trusts it in ONE direction — a present `true` may grey a
  session out (on first sight, and as a latch on any later re-send), but its
  absence never turns one green again. That covers both halves of the live bug:
  a session idled before we connected, and one idled during an SSE drop, whose
  `stop` is gone from the replay ring either way. Waking on absence is what we
  must not do — the bridge re-sends `running` on every connect, so it would
  restart elapsed clocks constantly. Absent still means running for an older
  bridge, exactly as before.
- The feed is `SessionState.terminal` (RingBuffer<TerminalLine>).
- Edge states (error-outranks-waiting ring, 8-session ring scaling, approval
  queue "1/3" peek, revocable always-allow manager) are in Concepts.dc.html §2b.

## Usage screen (page 0, one swipe right of home — issue #57)

_Re-skinned per the Halo usage design; implemented in `HaloUsageScreen.kt`
(pure presentation math as file-level internal funs, pinned by
`HaloUsageFormatTest`). The wire carries USED-percent plus an optional
upstream-verbatim `severity` string per window (PROTOCOL.md "Usage")._

**Chrome:** a decorative top **TimeText** clock on every state — exactly the
InnerScreen idiom (`#7E7C76`, 20px floor size), NOT a tap target (the clock
is just a clock).

**Layout anchors** (2026-07-18 refinement): for the expected **n ≤ 3** rows
(and always for the 3-row skeleton) the **row stack is centered by itself**
(dead center — `usageChordWidthsPx` assumes rows straddle it, so the chords
are only honest there) and the eyebrow ALONE pins **TopCenter at 118px** (the
mock's eyebrow height, clear of the clock); the same anchors in Loading and
Data, so the eyebrow never jumps when the fetch lands. **n ≥ 4** keeps the
single centered column — a fixed header would collide with the taller stack.

**States** (fetch-on-open drives them; Idle renders as Loading):
- **Loading:** the eyebrow at 70% opacity (pinned as above), 3 skeleton rows
  dead-centered — header placeholder rects 96×15 and 58×22 (r8, `#22242A`) +
  an 8px track (`#22242A`), chord-fitted widths; alpha pulses 0.5↔1 over 1.2s
  ease-in-out, staggered 0.18s per row. Tag `haloUsageLoading` on the
  skeleton container.
- **Data:** eyebrow + rows per the layout anchors above. Eyebrow `REMAINING` /
  `USED` (19/500, letter-spacing 0.14em, `#63615B`) is **tappable** and
  toggles the mode (tag `haloUsageMode`) — screen-local UI state
  (rememberSaveable), default REMAINING.
  One row per wire window, wire order: header line (name 22 `#8D8B84` · reset
  19 `#63615B`, `usageResetLabel` below · percent 30/500 pushed to the right
  edge) over an 8px r4 bar (track `#3A3C42`, fill width = shown percent).
  Compact when n ≥ 4: percent 25, in-row gap 5px (else 8px), 17px stack gaps.
  A freshness label (19, `#8D8B84`, tag `haloUsageStale` — the historical
  cache-only name, kept for the instrumented tests, on the TAPPABLE node)
  renders **under the last bar** in both layouts once the data is MORE THAN
  A MINUTE old (under that: nothing — fresh bars need no caveat; the ~30s
  ticker pops it in on time): pinned (n ≤ 3) at BottomCenter with 32dp
  bottom padding (the honest band between the stack and the page dots),
  compact (n ≥ 4) as the column's last child. **Tapping it is a manual
  force-refresh** (`onUsageRefresh` → `fetchUsage(force = true)`): the
  eyebrow's enlarged-target idiom (48×24 min, bottom-aligned glyphs so the
  slack grows upward, never over the page dots).
- **Error:** "usage unavailable" 27/500 `#E5484D`; the dynamic failure detail
  21 `#8D8B84` (single line, ellipsized); Retry pill (tag `haloUsageRetry`) —
  64px tall, 40px side padding, fully rounded, `#D97757` fill, "Retry" 24/500
  `#1A0F0A`. Retry re-fires the same fetch the page entry does.

**Fetch rate limit** (client-side, in `BridgeViewModel.fetchUsage`): the
upstream endpoint aggressively 429s pollers, so when fresh **live** bars are
on screen (`Data` with `source == "api"`, within 5 minutes of the last api
success) a NON-FORCED page re-entry is a complete no-op — no request, no
Loading flicker, instant re-entry. Only a successful api parse arms the
window; Error-Retry and cache-fallback entries always refetch.
`fetchUsage(force = true)` — the freshness label's tap — bypasses the
limiter (page entry / retry / auto-poll all stay non-forced).

**Silent refresh** (2026-07-18): a fetch started while `Data` is already on
screen — forced or not — never flips to Loading: the bars stay put and swap
when the result lands, and a FAILED silent refresh keeps the old Data (the
aging as-of label is the honest signal that refreshes are not landing).
Error/Idle starts still flip to Loading as before.

**On-page auto-poll** (2026-07-18, `HaloApp.PagerLayer`): while the usage
page is the current pager page AND the lifecycle is RESUMED
(`LocalLifecycleOwner` + `repeatOnLifecycle`), a loop fires the non-forced
`onUsageOpen` every `USAGE_AUTO_POLL_MS` = 310s — the VM's 300s limiter plus
a 10s buffer so the poll always lands past the window despite clock jitter.
STRICTLY FOREGROUND-ONLY (user directive): leaving the page, leaving the
screen, backgrounding, or ambient all cancel/suspend the loop; returning
restarts the wait from zero (the page-entry fetch covers the return case —
the loop only handles sit-and-watch). The silent-refresh rule makes each
swap invisible.

**Reset labels** (`usageResetLabelVariants(resetsAt, nowMs)`, 2026-07-18
refinements — one UNIFORM time-to-reset rule, no kind parameter; a session
window is always < 24h out so it naturally gets the relative form), now a
DEGRADATION LADDER of variants, longest first: delta ≤ 0 → `["resets soon",
"soon"]`; delta < 24h → `["resets in 3h 40m", "reset 3h 40m", "3h 40m"]`
(hours omitted when zero — "resets in 42m"; minutes always shown and
FLOORED); delta ≥ 24h → `["resets Sat 10am", "Sat 10am"]` (weekday + local
12-hour clock, lowercase am/pm, 12am/12pm never 0am, minutes only when
non-zero — "Sat 10:30am"). Malformed/absent resetsAt → empty list = no reset
line, never a dropped bar. `usageResetLabel` stays as the ladder's head.
`UsageRow` renders the ladder width-aware: a `BoxWithConstraints` in the
reset's slot measures each rung with `rememberTextMeasurer` against the
space the row actually left (name keeps natural width — the name-wins
truncation priority survives) and renders the FIRST variant that fits;
ellipsis only when even the shortest rung overflows.

**Freshness label** (`usageUpdatedLabel(fetchedAtMs, nowMs)`): null (no
label) under 60s; then full words with honest singular/plural — "as of 1
minute ago" / "as of 5 minutes ago" (< 60m) / "as of 1 hour ago" / "as of
3 hours ago". `UsageUi.Data.fetchedAtMs` is non-null in the client model —
a cache result keeps the bridge's value (the data's true age), a live api
result is stamped at parse time (the wire still only sends fetchedAtMs for
cache fallbacks).

**Minute ticker:** both label families are computed from NOW and nothing else
recomposes while the page sits open, so the screen keeps a remembered tick
incremented by a `LaunchedEffect(Unit)` loop (`delay(30_000)`); the tick keys
the sampled `nowMs`, so the labels recompute every ~30s and the loop dies
with the screen.

**Display names** (presentation-only): kind `session` → "Session",
`weekly_all` → "Weekly", any other kind keeps its wire label (e.g. "Fable").

**Chord-fitted widths** (widened 2026-07-18: factor 0.97 → 1.06, cap 336 →
360 — the mock's chord was conservative; every row gains ~6–9% and even the
top n=3 row's 332px sits well under the physical screen chord ≈432px at that
height): row i of n gets `min(360, round(2·√(max(R²−dy², 115²))·1.06))` px
at the 450 ref, with R = 169, dy = (i−(n−1)/2)·pitch, pitch = 63 (n≤3) / 54
(n=4) / 46 (n≥5); each row is individually centered so the stack hugs the
circle (n=3 ⇒ 332/358/332).

**Semantic tiers** — SEVERITY-FIRST, never from the shown number: the wire's
`severity` is the server's own (undocumented-threshold) color coding, so when
present and non-`"normal"` it is authoritative — lowercase it;
`crit`/`exceed`/`error`/`block` substrings ⇒ "out", any other non-normal
value ⇒ "low". The LOCAL fallback from REMAINING = 100 − wire percent
(matching the official screen's recalled coding: orange at 75% used, red at
95%): remaining ≤ 5 "out" ⇒ bar + percent `#E5484D`; ≤ 25 "low" ⇒ `#D97757`;
otherwise bar `#6CB289`, percent `#F4F1EA`. Final tier = the more severe of
server and local — the server escalates, never downgrades the local floor.

**Mode:** REMAINING shows remaining% (number and bar); USED shows used% — and
a **truly drained** window (remaining ≤ 0 — deliberately NOT tier "out",
which now starts at 5% remaining: a 95%-used bar must still read as 95%) in
USED mode pins the bar to a full 100%. Flipping the mode never changes tier
colors.

## Background lifetime (#24)

The engine is PROCESS-scoped (`BridgeViewModel.singleton`; MainActivity is
just an attachment point) and held open by **`BridgeSessionService`**, a
started `connectedDevice` foreground service: it starts when the UI state
turns paired (from the RESUMED activity), surfaces as a low-importance
notification carrying an **OngoingActivity chip** on the watch face
(`serviceStatusText`: "connected" / "reconnecting" / the state's name —
chip-short by design), and stops itself on the terminal connection states
(Stopped / AuthExpired / BridgeMismatch — a chip for a dead connection would
lie). `START_STICKY`: a system restart revives the connection with no
activity involved.

**Stop affordance:** the notification's **Disconnect** action —
`disconnect()`, the middle teardown: stream down, reconnects cancelled,
credentials and the persisted replay cursor KEPT (unpair remains the only
wipe).

**Catch-up:** the persisted cursor advances only on reducer-APPLIED frames
(#48's ack-to-advance), so every reconnect — `resume()` fires on every
activity ON_START and every service (re)start — sends `Last-Event-ID` =
last applied id and the bridge replays exactly what the watch never
rendered. Reopening the app after a Disconnect or process death IS the
catch-up path; nothing else is needed.

**Ambient** (`AmbientLifecycleObserver` → `HaloApp(ambient = …)`), the
wrist-down terminal, deliberately minimal: a 0.55 black scrim over the whole
root (TimeText stays visible underneath — it is the ambient clock), the
infinite animations frozen (`LocalHaloAmbient`; currently the usage
skeleton's pulse), and testTag `haloAmbient` present ONLY while ambient.
Wake restores the full screen in place.

## Actionable notifications (#25)

While the app is backgrounded, a permission request arriving over the live
SSE stream (which the #24 service keeps open) buzzes the wrist as a
HIGH-importance notification whose actions answer WITHOUT opening the app.
Implementation in `ApprovalNotifier.kt` (pure content model + queue diff,
plain-JVM-tested in `ApprovalNotifierModelTest`; the collector's gating /
swallow / teardown-bookkeeping branches JVM-tested against the
`ApprovalNotificationSink` seam in `ApprovalNotificationCollectorTest`)
hosted by `BridgeSessionService`; end-to-end in
`ApprovalNotificationFlowTest`, including the blank-reply drop.

- **Channel:** `"approvals"`, `IMPORTANCE_HIGH` — the importance IS the buzz
  (no custom vibration code; the platform and the user's channel settings
  own the pattern). Category `CATEGORY_REMINDER`: CALL-class urgency would
  hijack Wear's call surface, RECOMMENDATION is ranked down as passive
  content; "the agent is blocked on you" is a reminder-class act-now.
- **Per-permissionId tagging:** `notify(tag = permissionId, id = 25)` /
  `cancel(tag, 25)` — one notification per prompt, never a mutating
  singleton; concurrent prompts coexist and resolve independently, and an
  unchanged id is never re-posted (`setOnlyAlertOnce` as the belt to the
  diff's braces).
- **Action wiring:** one action per canonical `PendingPermission.options`
  entry VERBATIM (behavior-keyed, order kept — #17's rule, never inferred
  from labels), capped at 3 (Wear's action limit; the in-app card renders
  any overflow, and the content tap opens it). Wear forbids
  BroadcastReceiver actions, so every action is `PendingIntent.getService`
  to `BridgeSessionService` (`ACTION_ANSWER` + id/behavior extras), which
  answers through the SAME ViewModel entry points as the in-app card —
  ack-gated, 404-drops, retryable failures keep the prompt queued in-app.
  PendingIntent identity ignores extras, so distinct requestCodes derived
  from (permissionId, behavior) — plus a per-pair data URI as the second
  key — keep concurrent prompts' intents from recycling into
  approve-the-wrong-permission. Plain actions are `FLAG_IMMUTABLE`.
- **RemoteInput rule:** a SINGLE-question AskUserQuestion prompt gets one
  "Reply" action (`RemoteInput`, `FLAG_MUTABLE` — immutable would strip the
  results on API 31+); blank/null replies are DROPPED, never sent. ML Smart
  Replies are banned on the action (`setAllowGeneratedReplies(false)`) —
  live-demo lesson #1: Wear's generated chips ("Good question") rendered
  exactly like agent options, and a mis-tap answers a blocked session with
  Google's guess. The question's own labels ride `setChoices` too, but —
  live-demo lesson #2 — this Wear image renders those chips NOWHERE (the
  card's chip row belongs to the banned smart-reply machinery), so the
  labels ALSO become plain one-tap ACTION BUTTONS, the one surface that
  renders deterministically: single-select questions whose FULL option set
  fits next to Reply under the 3-action cap (≤ 2 options) get one button
  per label, answering through the same answerQuestions path as a typed
  reply (`EXTRA_ANSWER_TEXT`). All-or-nothing: 3+ options or multiSelect
  render NO buttons (a truncated menu would misrepresent the question) —
  Reply + the in-app card own those. A MULTI-question prompt gets NO
  actions at all — a wrist notification cannot walk the buffered
  multi-question form; the in-app card owns it.
- **Foreground gating (post-on-background since #59):** posts only while
  the app UI is not visible (`AppVisibility`, flipped by MainActivity
  ON_START/ON_STOP — now a StateFlow the collector OBSERVES, with the old
  `uiVisible` var kept as a facade). While visible the in-app card is the
  surface and nothing posts — but the withhold is no longer permanent: a
  prompt that arrived while visible and is STILL queued when the UI goes
  away posts on that visible→hidden edge. The old "never posted later
  either" rule silently muted every replayed catch-up prompt (the
  reconnect replay lands ~1s after every app open, while visible, and the
  card needs a centerpiece tap to even open), which gutted the wrist-buzz
  contract in exactly the AFK scenario. Uniform for replayed and live
  arrivals. Departures always cancel regardless (idempotent), including
  while visible.
- **Cancellation = queue-diff:** the collector diffs `permissionQueue`
  emissions; ids that leave the queue cancel their tag. Answered here,
  answered elsewhere (404), expired, permission-cleared, local dismiss —
  all flow through the reducer/ViewModel's queue removal, so the diff is
  the single cancellation path (no separate cleared listener to drift).
  Service death cancels everything it posted (dead actions are dead ends —
  the #24 zombie-notification reasoning); a restarted service's fresh
  collector re-posts whatever is still pending. An answer tap that lands
  after the user's Disconnect resumes the engine just to deliver the
  answer, but returns `START_NOT_STICKY` — a tap never re-mints the sticky
  promise the Disconnect revoked.
- **Restart edges (#59):** everything above assumed a graceful death; a
  process killed WITHOUT onDestroy (LMK, OEM swipe-kill) leaves shade
  survivors no in-memory set remembers. Three fixes, all riding the
  existing surfaces:
  - *Adoption:* at attach the collector adopts every active notification
    with `id == 25` (tags ARE permissionIds) into knownIds+postedIds, so
    the ordinary diff owns them again — no zombie Approve/Deny lingering
    forever after a prompt was resolved from the desktop while the watch
    was dead, and `cancelAllPosted` (graceful death) sweeps adopted
    survivors too, which is what keeps adoption self-limiting (a clean
    stop leaves an empty shade; the next attach adopts nothing).
  - *Post-Connected settle window:* the queue right after attach is
    pre-replay EMPTY, so departure processing for ADOPTED ids is deferred:
    queue emissions only ever GRADUATE a survivor (an id seen in the queue
    is proven pending and becomes an ordinary live id), and the verdict is
    a TIMER — Connected arms a settle window (`REPLAY_SETTLE_MS`, ~3 s,
    comfortably past the ~1 s backlog replay), whose close cancels exactly
    the never-re-confirmed leftovers against the freshest queue. NOT
    emission-gated, deliberately (second-round review): the reducer emits
    once per replayed frame, so "the first post-Connected emission" can be
    a PARTIAL pending set (adjudicating wholesale on it cancelled a
    still-pending survivor and re-buzzed it one emission later), and when
    the replay changes nothing — the only pending prompt resolved while
    the watch was dead, the issue's headline orphan — the
    distinctUntilChanged'd queue never re-emits and an emission-gated
    verdict never fires at all. A still-pending survivor keeps its
    ORIGINAL notification untouched (no cancel+re-post buzz —
    `setOnlyAlertOnce` does not survive a cancel); one resolved while dead
    cancels when the window closes, queue emission or not. Live ids keep
    immediate diff semantics throughout.
  - *Answer deferral:* a notification tap that itself recreated a dead
    process runs before the replay repopulates the queue; answering
    synchronously would be silently dropped by the ViewModel's still-queued
    guard (which stays intact — it protects the card UI). The SERVICE now
    defers an answer whose id is not yet queued: bounded wait (~10s,
    `ANSWER_REPLAY_WAIT_MS`) on `vm.state` for the id to appear, then the
    same entry point with the same payload; on timeout it does NOTHING
    (the re-posted notification / in-app card is the retry surface). All
    three answer kinds (behavior, RemoteInput text, option label) route
    through it. The tapped notification still cancels instantly either
    way. A double-delivered tap (racing the instant cancel) is dropped by a
    time-bounded claim (`claimAnswerDelivery`, 5 s window — long enough to
    kill any duplicate, short enough that a replay-re-raised prompt's fresh
    tap answers): without it both deliveries pass the still-queued guard
    (the first POST is async) and the duplicate's 404 clobbers
    decisionResult.
  - *Visibility-flap debounce:* activity recreation flaps the visibility
    flow true→false→true, and an unfiltered transient hidden edge would
    buzz every withheld prompt over the very card the user is looking at —
    the collector's edge handling rides `collectLatest` + a
    `VISIBILITY_FLAP_DEBOUNCE_MS` (400 ms) hold, so a flap back to visible
    cancels the pending post-on-background pass mid-delay
    (virtual-time-tested).

## Glanceables (#28)

A ProtoLayout **Tile** (`glance/HaloTileService`) and a **SHORT_TEXT
complication** (`glance/HaloComplicationService`), both rendering one shared
pure derivation: `glanceStatus(ConnectionState?, HaloModel?) → GlanceStatus
(healthy, statusText, detailText, shortText)` in `glance/GlanceModel.kt`,
plain-JVM-tabled in `GlanceModelTest`.

- **The honesty rule** (the reason the issue exists): status reflects ACTUAL
  STREAM HEALTH. `healthy` is true for exactly one state — `Connected` —
  never for paired/credentials-exist. The watchOS complication this replaces
  derived green from optimistic pairing state and glowed through outages;
  `GlanceModelTest.reconnectingWhilePairedWithLiveSessionsIsNeverHealthy` is
  that bug as a permanently failing sabotage trap. Unhealthy accent is Halo
  **Error red** (the offline screen's headline color), healthy is **Running
  green** — terracotta stays reserved for "waiting for YOU".
- **Peek, never start (passivity):** glanceables read state via
  `BridgeViewModel.peek()` — returns the singleton or NULL without
  constructing (constructing fires `engine.start()`); a tile-carousel swipe
  must not spin up the network. Null peek renders as honest
  "disconnected / tap to open" (`peekGlanceStatus`). The instrumented seam
  is `GlanceStateSource.resolver` (the #25 viewModelResolver pattern),
  restored in `@After`.
- **Census reuse:** the connected headline is the home ring's census
  wording VERBATIM via the extracted `sessionCensusText`/`haloCensusText`
  (HaloAllPage.kt) — same fact, same words, and the census comes from
  `HaloModel.from`, so honest-hidden sessions (#53) and queue orphans are
  already folded in. Detail line: `N waiting` (the approval card's wording)
  beats `N projects`.
- **Push points:** a third collector in `BridgeSessionService.onCreate`
  derives GlanceStatus from `combine(connection, state)` and calls
  `requestGlanceRefresh` (tile updater + `requestUpdateAll`) on
  **distinctUntilChanged CHANGE only** — the platform enforces a ~30 s tile
  update floor, and status/census changes are rare while output frames
  dedupe to nothing. Service BIRTH fires one explicit refresh (the last
  pushed render predates this process) and DEATH fires one final refresh in
  `onDestroy` — the re-request lands on peekGlanceStatus reading the
  terminal state, flipping the glanceables to "disconnected" instead of
  freezing on the last healthy green (the exact watchOS staleness bug).
  The tile also sets `freshnessIntervalMillis` = 60 s as the passive net
  for a dead process. The complication declares `UPDATE_PERIOD_SECONDS`
  300: pushes stay the update mechanism; the 5-minute poll is the staleness
  BOUND for a process killed WITHOUT `onDestroy` (LMK/OEM kill — no
  death-flip push fires there, and push-only would freeze the face on the
  last healthy value indefinitely). Each poll costs one null-safe peek.
- **Short-form table** (SHORT_TEXT budgets ~7 chars; mapped in the PURE
  layer as `GlanceStatus.shortText` so tile and complication cannot
  drift): Connected → `"N sess"` (zero included, `"0 sess"`);
  Connecting/Reconnecting → `recon`; Stopped & null-peek → `off`; Pairing →
  `pairing`; PairFailed → `no pair`; AuthExpired & BridgeMismatch →
  `re-pair` (the FIX fits in 7 chars, "wrong bridge" doesn't); ProtoMismatch
  → `update`. Long form rides as the complication's content description.
- **Carousel preview:** `drawable/tile_preview` is a static ring-glyph
  brand mark (ic_bridge_chip in Running green on AMOLED black), NOT a fake
  layout screenshot — a hand-made render would silently rot the moment
  `tileLayout` changes. Instrumented coverage is proto-tree assertions
  (`HaloTileServiceTest`, `HaloComplicationServiceTest`) instead of the
  issue's adb/screenshot wording: the carousel isn't automatable on the e2e
  image, and the layout proto IS what the tile says — honesty is the
  load-bearing acceptance, rendering protos is the platform's contract.

## Halo v2 (epic #94) — pager list, chrome-free feed, persistent morphing ring

_The second design iteration (claude.ai/design project `095874f5-…`, file
"Claude Watch Halo.dc.html"), ported across slices #95–#104 and closed out by
the #104 finish sweep. Where this section contradicts anything above, THIS
section wins — the epic's constants table (issue #94) is the numeric source
of truth. Reference captures: `wear/design/halo-v2-current-*.png`
(uncommitted, from the HaloPreviewScreens harness); morph recordings:
`wear/design/halo-v2-morph-*.mp4`._

### The changelog as built

- **Session list → fullscreen pager** (`HaloSessionPager.kt`, S5): one
  session per screen — wrapping title (17sp/1.14 Medium, 52dp insets, top
  44dp) over a `model · mode · use%` subheading (9.5sp, 1.5dp dot
  separators, use ≥ 80 terracotta; parts render only when present, so
  PTY/hook sessions keep a clean card). Position halo: the scope's sessions
  as a DASHED ring (stroke 4, dash 2.5/11, alpha .65, state-coloured) with a
  solid hero highlight (stroke 10) on the selected card. ‹ › chevrons in
  48dp cells (18sp Light, TextSecondary per the #61 rule — implementer's
  call the epic left open); ‹/right-swipe on the first card is BACK, › is
  alpha-0 (cell kept) on the true end. Five-icon action arc along the bottom
  (26dp circles, centres r=72dp at 144/117/90/63/36°): centre ✕ kill / ⊘
  honest-hide live with today's exact semantics (ACP close-frame limits stay
  #88's scope), ◇ ◐ ▤ ⇄ disabled stubs at 0.35 alpha. Rotary steps cards
  (40px detent). Selection lives in the pure `HaloNavState`; the pager only
  renders `nav.sessionId` — every edge (spawn slot, empty scope, at-start
  back, kill-under-cursor self-heal via `healListSelection`) is JVM-pinned.
- **Feed → chrome-free** (`HaloSessionFeed.kt`, S6): header, state dot, ‹ ›
  sibling cycling, "n of m · project" meta and the waiting banner all
  removed. The terminal tail fills the screen inside a soft circular mask
  (offscreen compositing + radial DstIn: opaque to 168 ref-px, transparent
  by 194 — inside the ring channel's inner edge, so text can never clip the
  ring at any scroll position) with resting insets top 30 / bottom 48 /
  sides 31dp as contentPadding (lines dissolve in the fade band instead of
  shearing). Touch scrolling joins rotary on the reversed list; swipe right
  = back (the at-top pull-down back died in the v3 purge — see the gesture
  model v3 section); while the session waits the WHOLE feed surface is the
  prompt's tap target (no pending → no click handler at all). Dictate
  pill/unavailable variant keep the bottom slot (microphone icon;
  unavailable = same icon struck ⊘ — see the #104 feedback section).
- **ONE persistent ring** (`HaloRingHost` + `HaloRingState` + `HaloRingMath`,
  S2/S4/S7): fixed channel radius 214 ref-px for every stroke; arc k ENDS at
  −94° − k·(360/n), winds anticlockwise, gaps 8.5° (8° solo). Paint and
  geometry are separate channels (.3s ease-in-out vs .55s decel, +220ms
  geometry delay when one update does both, 850ms window); vanishing arcs
  collapse onto their own start blending to black; new arcs snap in
  pre-coloured, fade .3s, drawn beneath the settled ring for 1300ms. Level
  morphs: dash split/merge (page↔list, .5s, stroke 9↔4, alpha 1↔.65, hero
  9↔10, close-swap at 1000ms), grow/shrink (list↔feed, .65s, sweep↔360°
  symmetric, stroke 10↔6), highlight rotation .4s shortest-path accumulated
  with the 2-session backstep retrace. Content crossfades ride the morphs
  (out .25s, in .45s delayed .1s; list→page return .3s). Non-adjacent jumps
  (Answer-pill page→feed, jump-home) snap — they happen under the opaque
  card. Ambient snaps to targets, never freezes a mid-morph frame.
- **Navigation** (S1/S3, since revised by gesture model v3 — see its own
  section below): nav owns the page — `HorizontalPager` is GONE from the
  main pages (the design has no drag-follow); horizontal swipes/dot taps
  change `nav.page` and only content slides (300ms/70px/HaloEasing). Tap
  the face → the scope's session list (v3: the ONLY list entry — the
  swipe-up drill is gone); feed: swipe right → list; no wrap. The
  centerpiece tap's old jump-to-prompt job moved to the Answer pill.
- **Main screens** (S3, re-derived in the #104 feedback round): Answer pill
  (25dp tall, terracotta, "Answer" 11sp SemiBold on `#1A0F0A`) whenever the
  scope has a prompting session — out of flow, its top DERIVED as the
  re-centred clock group's bottom + the prototype's 21px clearance
  (`Halo.Geo.AnswerPillTopPx`, ≈153dp; supersedes S3's screen-absolute
  154dp), so the clock+subtitle group NEVER shifts; "↑ sessions" hint
  removed; clock group identical on All and project pages and centred on its
  VISUAL extent (see the feedback section below).
- **Wire** (S8/S9, #97/#102): the adapter's `usage_update` /
  `current_mode_update` / `config_option_update` tee lands on `session`
  events as the additive `model` / `mode` / `contextPct` trio (ACP sessions
  only; absence preserves, presence-keyed — 0 is a real contextPct), and the
  watch maps them onto `HaloSession.modelName/modeName/usePercent` for the
  pager subheading (display rules: "Claude " prefix stripped; mode ids
  through the short-label map — default→manual, acceptEdits→edits,
  bypassPermissions→bypass, dontAsk→no-ask, unknown verbatim).

### The ring engine as landed (architecture)

One set of per-slot geometry Animatables (`end`/`sweep`/`color`/`alpha`/
`presence`), rendered up to three ways from the SAME numbers:

- **Solid layer** — the page ring. Slot `alpha` carries lifecycle here
  (arrivals fade in, corpses hold 0); under every non-PAGE level and through
  the whole close it is pinned 0 by `planRetarget(solidHidden = true)`.
- **Dashed layer** — same slot geometry, dash paint. Everything about it is
  the ONE merge fraction: `dashIntervals`/`dashStroke`/`dashLayerAlpha` are
  all functions of it, so interval, stroke and alpha cannot desync — and at
  fraction 1 it is pixel-identical to the solid layer by construction. Slot
  `presence` (the #104 carry-over fix) multiplies in per arc: arrivals and
  departures fade on the dashed layer exactly as slot alpha fades them on
  the solid layer (`alpha` itself can't serve — it is pinned 0 under
  solidHidden, which is why S4-era dash arrivals popped).
- **Hero arc** — the list highlight AND the feed ring, one set of channels.
  OPEN snaps it onto the selected segment (deliberate divergence from the
  prototype, whose stale rotation would visibly spin in) then thickens 9→10;
  steps rotate shortest-path on ACCUMULATED angles (winding history
  preserved; 2-session backstep forces the +180° retrace); GROW expands both
  ways into the full circle from its ACTUAL pose (interrupt-safe); SHRINK is
  the exact reverse with a nearest-coterminal correction onto the real
  segment. The hero never fades in morphs — only stroke weight and the .85
  feed alpha ease. The one sanctioned hero fade is the spawn-card selection.

**The close-swap**: list→page merges the dashes 0→1 while the hero thins
10→9; the REAL solid layer stays hidden throughout; at the 1000ms settle the
swap lands in ONE frame — solid alphas (and presences) snap up, dashed layer
and hero vanish. Plans are computed from COMMITTED targets, never mid-flight
values (settled poses reproduce bit-exactly — the zero-motion contract);
interruptions retarget from current values via the Animatable mutex; the
settle waits on the FRAME clock, so JVM manual-clock tests drive it
deterministically.

**⚠ Round-caps invariant (LOAD-BEARING — do not "clean up")**: the dashed
layer draws with `StrokeCap.Round`, and at merge fraction 1 its zero
off-interval is drawn as a CONTINUOUS stroke (`pathEffect = null`, never a
degenerate dash pattern). Both halves exist so that merge-1 dashes render
EXACTLY as the solid stroke — width, endpoints, and cap shape included.
The close-swap's atomicity (hide real solid layer → swap at settle) is
pixel-invisible only because of this identity: switch the dashed cap to Butt
(or draw merge-1 through the dash path-effect) and every list→page return
flashes at the seam. `HaloRingMorphTest` pins the swap on the emulator;
the cap identity itself is a draw-time property only eyes (and this note)
protect.

**The trigger**: `snapshotFlow` over a value-comparable `RingInputs`
snapshot (level, scope states, selection index, step direction, feed state,
empty style) — clock ticks and streaming feed lines rebuild an EQUAL
snapshot, so they provably cannot restart animations (the design
prototype's clock-tick bug, the reason v2 exists). Morph phase lives inside
the engine and is never exposed as snapshot state for content to key on.
Equal inputs launch nothing; unrelated updates never retime an in-flight
tween.

### Gesture model v3 (#109, user-decided 2026-08-02 — binding)

Supersedes both the imported design's "vertical = depth" IA above AND
rounds 1–2's hierarchy-back framing of #109 (round 2's machinery — the
always-enabled PredictiveBackHandler, the SystemBackDragClaim stand-down —
survives; its ROUTE changed). The model:

- **One horizontal axis.** Swiping RIGHT always moves one step
  leftward/backward through the app's spaces; swiping LEFT moves one step
  rightward/forward. The full rightward chain: FEED → its LIST card →
  previous sessions one by one → first card → its PAGE → project pages
  toward HOME → USAGE → SETTINGS → **exit the app**. That settings-edge
  exit (swipe-right or a back at settings-at-rest) is the ONLY exit in the
  entire app — a deliberate `activity.finish()`; no other screen may exit.
- **System back = the same step.** The root handler's completion routes the
  identical one-step-back the surface swipe-right performs
  (`systemBack(nav, overlayOpen, model)` in HaloNav.kt, JVM-pinned): on the
  list that means step-to-previous-session — the page is reached only from
  the first card (the user's explicit choice). Overlay priority stays:
  back dismisses the topmost overlay (voice/card/picker) first. Hardware
  KEYCODE_BACK routes identically. No fall-through, no disarm, ever
  (round 2's always-registered invariant).
- **The vertical purge — "everything vertical goes":** the depth screens'
  swipe-down back, the feed's at-top pull-down back, the pages' swipe-up
  drill, the cards' swipe-down decide-later/answer-later, the voice
  overlay's pull-down cancel and the spawn picker's pull-down cancel are
  ALL removed (the shared overscroll-exit connection with them). Tapping
  the face/centerpiece is the only list entry. NOT navigation and NOT
  removed: feed/usage/settings content scrolling, rotary input, the card
  option lists.
- **Every overlay keeps a non-gesture escape:** the cards' explicit
  buttons ("decide later"/"answer later", now without the stale ↓), the
  voice overlay's Cancel/OK/Discard-Retry, and the spawn picker's cancel
  row (the once-passive "↓ cancel" label made tappable — the pull-down was
  its only visible escape) — plus the system back over all of them.

### Deviations ledger (v2, all deliberate)

| Deviation | Rationale |
|---|---|
| Answer pill top **DERIVED per surface** (was 154dp screen-absolute) | The epic table's "119dp absolute" is a verified mislabel: 119dp is the prototype's coordinate INSIDE its 70px-inset face container (238px + 70px = 308px ⇒ 154dp), and that absolute number is itself just "clock-group bottom + 21px" in the prototype's box-centred geometry. The #104 user feedback re-based both surfaces on their anchor groups: main pages = re-centred clock-group bottom + 21px (`Halo.Geo.AnswerPillTopPx` ≈ 306 ref-px ⇒ ≈153dp — the group bottom itself sits higher than the mock's 287px because Compose gives the lone clock line the full font box, see `Halo.Geo.ClockLineBoxPx`); pager = IN FLOW below the card's text stack + 22px (`PILL_CARD_CLEARANCE`, the prototype's own pager geometry — its 7px column gap + 15px pill margin), which is what clears the action arc. S5's "reuse the main pages' 154dp on the card" is retired: consulted against the prototype, the pager never shared the home slot. |
| Fixed ring channel **214** ref-px (design: 205) | The v1 outer-edge-derived radius re-centres per stroke; morphs animate stroke WIDTH, so a per-stroke radius would breathe radially. One channel, ring fattens/thins in place. |
| Anchor **−94°**, gaps **8.5°/8°** (was −90°-centred/10°) | Design geometry adopted verbatim. |
| Microphone-ICON dictate pill (was: hand-drawn mic glyph) | SUPERSEDED by the 2026-08-01 #104 user feedback (which also supersedes the design's text "Dictate" pill): available = a real microphone icon (`drawable/halo_mic`, the Material mic path, TextPrimary); unavailable = the SAME icon muted (TextFaint) with a ⊘ crossed-circle overlay, non-interactive — #78's honest-unavailability semantics and the `haloDictate`/`haloDictateUnavailable` tags kept. |
| Curved page dots (+ outlined settings/usage dots at slots 0/1) | Pre-v2 user direction, kept; settings page at slot −2, usage at −1. |
| 200-line feed buffer (design demo: 30) | Pre-v2 user direction, kept. |
| Swipe-down-back on list + feed | REMOVED-BY-USER-DIRECTION (2026-08-02, #109 gesture model v3): the whole vertical-navigation family went — swipe-down backs, at-top pull-downs, swipe-up drill, card/voice/picker pull-down exits. One horizontal axis; tap-only list entry; settings-only exit. Supersedes the earlier "kept as the app-wide secondary back". |
| Spawn picker's tappable cancel row (was: passive "↓ cancel" label) | The v3 purge removed the pull-down cancel — the picker's ONLY visible escape — so the label became the smallest honest affordance: a real cancel row (`haloSpawnCancel`), with the system back as its twin. |
| Platform-curved non-tappable TimeText, **inset inside the ring channel** | Platform TimeText instead of a custom clock (pre-v2); the v2 sweep added `ClockRingClearance` outer padding — at the platform's 2dp rim padding the clock printed through the list/feed edge ring at 12 o'clock. |
| DELEGATED `#6BA8D8` + ERROR states | App-side state model is richer than the design's; luminance-matched blue, see the v1 data-mapping notes. |
| Spawn as trailing "+ new session" pager card | The prototype has no spawn affordance; the card is the All scope's true end (› hidden there) and the empty-scope content; ring highlight fades while it's selected. Same `haloSpawn` tag/picker as v1. |
| All-ring order **project-grouped** | Ring order must equal pager order (#95); user-visible change for interleaved projects. |
| ⊘ honest-hide for external sessions | #53 semantics ported into the action arc unchanged. |
| `#54/#55` detail line on the **pager card** | The chrome-free feed killed the FeedHeader that carried the ⎇ branch badge and ⚙ agents line; the pager card is their new home (see adjudications). |
| Chevrons/controls at TextSecondary (mock: `#3A3C42`) | The #61 readability rule applied to controls — the epic explicitly left the tint to the implementer. |

### #104 finish-sweep adjudications (carried from #101/#102/#103)

1. **TimeText vs the dotted ring at 12 o'clock (list/feed)** — FIXED by
   insetting, not hiding: the root TimeText now takes
   `Halo.Geo.ClockRingClearance` as outer arc padding (edge → glyph outer
   edge = 18 ref-px = 9dp: past the channel's deepest inner reach, the
   hero's stroke-10 inner edge at 209 ref-px, plus 2 ref-px clearance).
   Hiding was rejected because the ambient contract needs this TimeText as
   the wrist-down clock at EVERY depth, and ambient still draws the
   list/feed ring — the collision had to be solved geometrically anyway.
   One inset at all depths: the fixed clock never shifts when the ring
   morphs under it or collapses away on the glance pages. (The prototype's
   decoded copy was no longer available to consult; the epic carries no
   list/feed clock spec, so this is implementer's-call, documented here.)
2. **Dashed-layer arrival fade** — FIXED: new-session arrivals at LIST
   level now fade in on the dashed layer via the per-slot `presence`
   channel (0→1 on the 300ms new-arc spec; departures fade 1→0 on the
   paint spec — the vanishing-arc "alpha→0" the dashed layer previously
   had no channel to honour). JVM-pinned in `HaloRingStateTest`
   (arrival/departure fades + the mid-close arrival landing settled at the
   swap).
3. **list→page return fade = 300ms** — DOCUMENTED, no change: the epic's
   ".3s fast fade" IS 300ms (`Halo.Motion.ListToPageFadeMs`), applied to
   the ENTERING page content; the exiting list keeps the general .25s
   content fade-out. The prototype was unavailable to double-check the
   out-fade split; the epic's numbers win and are what shipped.
4. **Round-caps invariant** — documented above (the ⚠ block).
5. **Branch badge / agents line (#54/#55)** — REHOMED on the pager card:
   one faint ellipsized line under the subheading, exactly the retired
   FeedHeader's derivation (`⎇ branch[ · wt] · ⚙ N agent[s]`, agents only
   while > 0, null line when neither — PTY/hook sessions keep a clean
   card). Ring-blue alone was rejected as the answer: DELEGATED says
   "subagents somewhere", never which branch or how many, and it hides
   entirely while the main loop still runs. The feed stays chrome-free —
   the card is where session identity lives in v2. Pure seam
   `sessionDetailLine` JVM-pinned in `HaloSubheadingTest`; tag
   `haloCardDetails`.
6. **#102's live-ACP check** — PENDING ON THE WRIST (see below).
7. **(New, from the capture compare) Answer pill vs ✕ kill cell** — the
   pager pill at the shared 154dp sat squarely on the ✕ kill cell and grazed
   its neighbours, and with the pill inside the card the later-composed arc
   WON those taps — a finger aiming at Answer's lower half killed the
   session. The first fix hoisted the pill to the pager's topmost layer at
   the SAME 154dp ("every epic number is kept — the visual grazing is the
   design's own geometry, flagged for the user's design pass"). That stance
   is SUPERSEDED by the 2026-08-01 binding user feedback: the grazing was a
   deviation, not the design — consulted directly, the prototype's pager
   places the pill IN FLOW inside the card column (15px margin under the
   subheading), far above the arc; only the home/project pages use the
   absolute slot, and there it is "clock-group bottom + 21px". Both
   derivations are now implemented (see the feedback section below); the
   topmost-layer hoist is KEPT as defence-in-depth (same AnimatedContent
   key + spec, so the pill still slides in lockstep), with
   `answerPillOutranksTheKillCellInTheirOverlapBand` still tapping the
   pill's bottom band BY COORDINATE and the new
   `answerPillRidesTheCardGroupAndClearsTheActionArc` pinning the geometric
   clearance itself. Related capture note: on the feed, the inset clock
   sits over the mask's fade band at 12 o'clock — lines there are already
   dissolving, and the ring stays clear; left to the design pass.

### #104 user design feedback (2026-08-01, binding) — as built

Three items arrived mid-sweep and supersede any conflicting earlier
decision in this document:

1. **Compact clock (root TimeText): inset, not hidden** — brought DOWN off
   the rim so it stops clipping the halo: `Halo.Geo.ClockRingClearance`
   (18 ref-px = 9dp outer arc padding: past the channel's deepest inner
   reach — the hero's stroke-10 inner edge — plus 2 ref-px), applied at
   every non-page depth. Direction per the feedback: an inward offset, not
   removal (the ambient contract needs this clock at every depth anyway).
2. **Main centerpiece clock: centre line computed over clock + subheading
   AS A GROUP** — `Arrangement.Center` centres layout BOXES, and the
   clock's line box (the full ascent+descent font box ≈103 ref-px — a lone
   line never trims to its 88px/1 line height) hides ~19 ref-px of dead
   leading above the digit caps (Roboto ascent − cap height — derivation on
   `Halo.Geo.ClockDeadLeadingPx`), so box-centring rendered the visible
   mass low. The centerpiece now centres the group's VISUAL extent (digit
   cap tops → subtitle slot bottom) via a phantom spacer mirroring the
   dead band — the clock rides ~9.5 ref-px higher. Knock-on, also per the
   feedback: the Answer pill's offsets are re-derived from their anchor
   groups instead of screen-absolute 154dp — main pages = re-centred
   clock-group bottom + 21px (`Halo.Geo.AnswerPillTopPx`); pager = in flow
   below the card's text stack + 22px (the prototype's own pager geometry,
   verified against the prototype file), which clears the action arc.
   Pinned by `clockGroupCentresOnItsVisualExtentAndThePillHangsThePrototype-
   ClearanceBelowIt` and `answerPillRidesTheCardGroupAndClearsTheActionArc`.
3. **Dictation affordance: a real microphone ICON** — `drawable/halo_mic`
   (the Material mic path) replaces the hand-drawn Canvas glyph in the
   Dictate pill; the unavailable state (#78) is the SAME icon muted with a
   ⊘ crossed-circle overlay (`haloDictateMicOff`), non-interactive — no
   click action at all, keeping the honesty semantics. Supersedes the
   "mic glyph kept" ledger row (updated above). Reference captures:
   `halo-v2-current-08-dictate-mic.png` /
   `halo-v2-current-09-dictate-unavailable.png`.

### Pending on-wrist live checks (post-epic, user steps)

- **Subheading on a live ACP session** (#102's deferred criterion): with the
  real bridge (port 7860) and a Zed-born session, the pager card shows
  `model · mode · use%` live, the mode flips when Zed's mode changes, and
  use% tracks the context window. Fixture/e2e-level verification is done;
  the wire path is the same one the e2e drives — this is a confidence pass,
  not a gate.
- **v2 install on the physical watch**: the SM-L330 still runs the v1 APK;
  install runbook in the project memory (physical-watch-adb-connection).
  While there: eyeball the morph walk, the re-derived pill clearances
  (clock-group + card-group anchoring), the re-centred centerpiece, the mic
  icon states and the TimeText inset on real glass.
- **Renaming a Zed thread, wrist edition (#112)**: a manual rename in Zed's
  sidebar/title editor never crosses ACP — Zed persists it purely in its own
  thread store (verified in Zed's source; upstream limitation, no protocol
  method exists) — so the wrist keeps the transcript title after a UI
  rename. The rename that DOES reach the wrist is the in-thread `/rename`
  slash command (transcript `customTitle` → adapter poll →
  `session_info_update` → bridge re-announce; pinned by adapter + bridge
  tests). Worth one live confidence pass: `/rename` in a Zed thread, watch
  the pager card re-label at the turn's end.
