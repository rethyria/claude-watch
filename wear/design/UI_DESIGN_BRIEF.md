# Design brief: Claude Watch (Wear OS) — UI/UX concepts

## Ask
Produce **3–4 distinct UI/UX concepts** for a Wear OS app, each as annotated
mockups covering the key screens/states listed below. I want genuinely
different directions to choose from, not one design with variations — e.g. one
"glanceable status-first" direction, one "conversation/timeline" direction, one
"card-stack/action-first" direction. For the chosen direction I'll later want a
tighter spec, but this round is about exploring the space.

## What the product is
Claude Watch mirrors an AI coding agent (Claude Code / Codex running on a
laptop) to the wrist. A local "bridge" on the laptop streams the agent's
activity to the watch over the LAN. On the watch the user:
1. **Watches** the agent work — a live terminal/output feed, possibly several
   concurrent coding sessions.
2. **Approves or denies permission requests** the agent raises (e.g. "run
   `git push origin main`?") — this is the killer feature; the agent BLOCKS
   until the wrist answers, so the watch must make "what am I approving, for
   which session" instantly legible.
3. **Answers multiple-choice questions** the agent asks (an "AskUserQuestion"
   card — one or more questions, each with 2–4 options, sometimes free text).
4. **Sends commands** back by voice dictation or text.

It is a companion to work happening on the laptop, glanced at many times a day,
often for 2–5 seconds at a time, sometimes with a raised wrist mid-task.

## Platform constraints (hard requirements)
- **Wear OS** (Compose for Wear OS). Assume a **round** screen ~450×450px
  (design for round; corners are unusable — respect a circular safe-area inset).
  Also survives square/rectangular watches.
- **Small + far**: legible at arm's length; large touch targets (min ~48dp);
  minimal text per screen; one clear primary action per screen.
- **Glanceable first**: the most important state (connected? something waiting
  for me?) must read in <1 second without interaction.
- **Ambient mode**: a low-power always-on variant of the main screen (dimmed,
  minimal pixels lit, no color washes) — design an ambient state for the
  primary screen.
- **Input is awkward**: prefer taps and swipes; voice is the main text-entry
  path; typing is a last resort.
- **Rotary input** (bezel/crown) should scroll long content.
- Follow Wear OS Material design: `ScalingLazyColumn`, `Chip`/`Button`,
  `TimeText` at top, page indicators for horizontal pagers, `Vignette`.

## Screens / states to design (cover all)
1. **Connection / status home** — is the bridge connected, how many sessions,
   is anything waiting for me. The glanceable anchor.
2. **Live terminal / session view** — streaming agent output for one session,
   readable (not a raw JSON/log dump), scrollable, with a clear session title.
3. **Session pager** — switching between multiple concurrent sessions; must
   make "which session am I looking at, how many are there" obvious.
4. **Permission approval card** — shows the tool + the exact command/argument
   being requested AND which session it's from; primary actions
   Approve / Deny / Allow-always. This is the highest-stakes screen — a
   mis-tap approves a real action. Design for zero ambiguity.
5. **AskUserQuestion card** — 1–4 questions, each with options (single or
   multi-select) and sometimes a free-text answer; must handle >1 question
   without losing any.
6. **Command input** — voice dictation entry with an "ack-gated" echo: the
   spoken command should NOT appear as sent until the bridge confirms receipt;
   a failure must surface an error + retry, never a silent loss.
7. **Empty / error / disconnected / re-pair states** — including a short
   "reconnecting…" and a clear "tap to re-pair" path.
8. **Ambient variant** of screen 1 (and optionally 2).
9. **Custom watch face (status-only) — the product's OWN face, set as the
   user's active face (not a complication on someone else's face).** It renders
   the time plus Claude status and is the at-a-glance anchor for the whole
   product. It is **read-only**: tapping it launches the app, where every
   approval/command happens — the face never hosts action buttons. Design:
   - **Interactive (screen-on) state:** time + status. The four status states
     must be unmistakable at a glance — **disconnected**, **connected / idle**,
     **running** (agent working), and **waiting for you** (a permission or
     question is blocked pending an answer — the highest-priority state, must
     dominate the face). Show a session count when more than one is active.
   - **Ambient (wrist-down) state:** the always-on low-power variant — dimmed,
     minimal lit pixels, no color washes, updates ~once/minute, burn-in-safe
     (shift pixels, avoid large solid lit blocks). Time stays legible; status
     collapses to a single quiet indicator, but "waiting for you" must still be
     distinguishable in ambient.
   - **Watch-face editor/config screen:** the small customization UI a face
     exposes (color/theme choice, optional complication slots), even if
     minimal.
   - **Tap-to-open affordance:** tapping the face opens the app. Because
     Wear OS reserves/overloads the bare single-tap on a face, design a clear
     **tappable status zone** (a hotspot the user aims at) rather than relying
     on a tap anywhere. Show its tap state. The tap should **deep-link to the
     relevant screen**, not just the app home: when status is "waiting for
     you," one tap lands directly on the pending approval/question; otherwise
     it opens the session list — so it's one tap from glance to decision.
   - Round-first and full-bleed — a face owns the entire screen.
   Constraints for the designer to know: the face is a *renderer* of status fed
   by a background service; it can't hold a live connection and won't wake the
   user (urgent "answer now" is a separate notification). So it's the calm
   glance — freshness is on a cadence (~30–60s interactive, ~1 min ambient),
   and "waiting for you" must clear promptly once the prompt is answered
   elsewhere so it never lingers as a false positive.

## Current build — structure and problems (fix these)
Screenshots of the actual current build are attached (`current-01-home.png`,
`current-02-session-terminal.png`, `current-03-session-terminal-2.png`). The
app is a horizontal **pager**: page 1 is the status/pairing home, and each
subsequent page is one active coding session's terminal. Pages are unlabeled —
the only affordance is a row of dots at the bottom.

**Home screen (page 1):**
- Title "Claude Watch" is clipped by the round corner ("laude Watch").
- The full raw session UUID (`session:e9f9cdc0-8041-4d22-...`) is printed and
  overflows off the right edge — pure noise on a glanceable screen.
- The pairing form (host / port / code text fields) is shown even when already
  "paired, stream open" — connection setup and connected-status are conflated
  on one screen; the fields should collapse once connected.
- Fields and text run to the very edges (no round safe-area inset).

**Session/terminal pages:**
- Rectangular terminal layout jammed into a round screen; text runs to the
  edges and the top row clips on curved corners — no safe-area inset.
- Ragged left margins (raw terminal whitespace preserved) — looks messy.
- A single flat scroll mixes status dot, session title, log output, and a
  heavy filled ✕ button (top-right) at similar visual weight — no hierarchy,
  and the ✕'s purpose is unclear.
- Multiple text colors (amber / grey / white) line-to-line with no consistent
  semantic meaning.
- Small type, hard to read at a glance.

**Cross-cutting:**
- No labeled navigation — the user cannot tell what each of the N pages is
  without swiping through all of them.
- One "session per page" doesn't scale or signal how many sessions exist.

## Deliverables per concept
- Annotated mockups of screens 1–7 (+ the ambient state), at ~450×450 round.
- A one-paragraph rationale: what this direction optimizes for and its
  tradeoffs.
- A short type + color system: 2–3 type sizes, a small semantic palette
  (e.g. what "waiting for you" vs "running" vs "error" look like).
- Notes on the approval card specifically: how it makes the command + session
  unmissable and the primary action mis-tap-resistant.

## Tone
Calm, trustworthy, "at a glance." This is a tool that can approve destructive
actions on the user's real machine — clarity and confidence beat density and
cleverness. Optimize for the 2-second glance, not the power user studying it.
