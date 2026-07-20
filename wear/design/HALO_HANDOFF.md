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
  AskUserQuestion → waiting-q; else `activity==WORKING`/thinking → running; else
  idle. Per-session `error` is not modeled yet (offline is connection-level).
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
