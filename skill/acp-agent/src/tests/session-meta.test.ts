// claude-watch (#97, Halo v2 S8): the wrist subheading's `model · mode · use%`.
// The adapter's whole contribution is bookkeeping — ZERO new events:
//
//   1. the session register (and the attach re-register) seeds the bridge with
//      the model DISPLAY name (default-alias hop included), the mode id, and
//      the same context tokens the initial usage_update publishes;
//   2. noteSessionMeta keeps the channel's restart-replay copy current when
//      the model or mode changes mid-session — every mid-session writer
//      funnels through applyConfigOptionValue, so that one choke point is the
//      whole hook surface.
//
// Everything else the wrist sees mid-session rides the existing client tee.
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import type { Options } from "@anthropic-ai/claude-agent-sdk";
import type { AcpClient, ClaudeAcpAgent as ClaudeAcpAgentType } from "../acp-agent.js";
import type { BridgeChannel } from "../bridge-channel.js";
import { makeMockQuery } from "./helpers.js";

let capturedOptions: Options | undefined;
vi.mock("@anthropic-ai/claude-agent-sdk", async () => {
  const actual = await vi.importActual<typeof import("@anthropic-ai/claude-agent-sdk")>(
    "@anthropic-ai/claude-agent-sdk",
  );
  const { makeMockQuery: makeQuery, DEFAULT_CONTEXT_USAGE } = await import("./helpers.js");
  return {
    ...actual,
    getSessionInfo: vi.fn(async () => null),
    getSessionMessages: vi.fn(async () => []),
    query: (args: { prompt: unknown; options: Options }) => {
      capturedOptions = args.options;
      return makeQuery({
        // Live-shaped: the default alias names its resolution, and every
        // identity string is free of a "1m" token so the seeded window is the
        // deterministic DEFAULT_CONTEXT_WINDOW.
        initializationResult: async () => ({
          models: [
            {
              value: "default",
              resolvedModel: "claude-sonnet-5",
              displayName: "Default (recommended)",
              description: "Use the default model (currently Sonnet 5)",
            },
            {
              value: "sonnet",
              resolvedModel: "claude-sonnet-5",
              displayName: "Sonnet",
              description: "Sonnet 5 · Efficient for routine tasks",
            },
          ],
        }),
        getContextUsage: async () => DEFAULT_CONTEXT_USAGE,
      });
    },
  };
});

vi.mock("../tools.js", async () => {
  const actual = await vi.importActual<typeof import("../tools.js")>("../tools.js");
  return { ...actual, registerHookCallback: vi.fn() };
});

function makeMockClient(): AcpClient {
  return {
    sessionUpdate: vi.fn(async () => {}),
    requestPermission: vi.fn(async () => ({ outcome: { outcome: "cancelled" } })),
    readTextFile: vi.fn(async () => ({ content: "" })),
    writeTextFile: vi.fn(async () => ({})),
  } as unknown as AcpClient;
}

function makeFakeBridge(): BridgeChannel & Record<string, ReturnType<typeof vi.fn>> {
  return {
    registerSession: vi.fn(),
    deregisterSession: vi.fn(),
    forwardSessionUpdate: vi.fn(),
    forwardPermissionRequest: vi.fn(),
    forwardPermissionResolved: vi.fn(),
    forwardTurnBoundary: vi.fn(),
    noteSessionTitle: vi.fn(),
    noteSessionMeta: vi.fn(),
    onInject: vi.fn(),
    onPermissionDecision: vi.fn(),
    takePendingPickup: vi.fn(async () => null),
    start: vi.fn(),
    stop: vi.fn(),
  } as unknown as BridgeChannel & Record<string, ReturnType<typeof vi.fn>>;
}

let ClaudeAcpAgent: typeof ClaudeAcpAgentType;
let cacheHome: string;
let previousCacheHome: string | undefined;

beforeEach(async () => {
  capturedOptions = undefined;
  // Isolate the persisted context-window cache: a learned window on the host
  // machine must not change the seeded size this suite asserts on.
  previousCacheHome = process.env.XDG_CACHE_HOME;
  cacheHome = mkdtempSync(path.join(tmpdir(), "acp-meta-cache-"));
  process.env.XDG_CACHE_HOME = cacheHome;
  vi.resetModules();
  const mod = await import("../acp-agent.js");
  ClaudeAcpAgent = mod.ClaudeAcpAgent;
});

afterEach(() => {
  if (previousCacheHome === undefined) delete process.env.XDG_CACHE_HOME;
  else process.env.XDG_CACHE_HOME = previousCacheHome;
  rmSync(cacheHome, { recursive: true, force: true });
});

describe("session register carries the subheading meta", () => {
  it("newSession seeds model (default-alias hop), mode, and the context tokens", async () => {
    const bridge = makeFakeBridge();
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);

    const response = await agent.newSession({ cwd: process.cwd(), mcpServers: [] });

    const registerSpy = bridge.registerSession as unknown as ReturnType<typeof vi.fn>;
    const call = registerSpy.mock.calls.find(
      (c: any[]) => c[0].sessionId === response.sessionId,
    )![0];
    // The default alias resolves THROUGH claude-sonnet-5 to the named row —
    // never the "Default (recommended)" mouthful.
    expect(call.model).toBe("Sonnet");
    // The mode the register announces is the mode the response reports.
    expect(call.mode).toBe(response.modes!.currentModeId);
    // A fresh session: nothing used yet, against the seeded (heuristic)
    // window — the SAME numbers the initial usage_update publishes to Zed.
    expect(call.contextUsed).toBe(0);
    expect(call.contextSize).toBe(200000);
    expect(capturedOptions).toBeDefined();
  });

  it("the attach re-register carries the session's CURRENT meta, not its birth values", async () => {
    const bridge = makeFakeBridge();
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);
    // A live detached (watch-spawned) session that has drifted since birth:
    // switched model, entered acceptEdits, burned some context.
    agent.sessions["watch-1"] = {
      sessionId: "watch-1",
      cwd: "/test",
      cancelled: false,
      detached: true,
      sessionFingerprint: JSON.stringify({ cwd: "/test", mcpServers: [] }),
      modes: { currentModeId: "acceptEdits", availableModes: [] },
      models: { currentModelId: "opus[1m]", availableModels: [] },
      modelInfos: [{ value: "opus[1m]", displayName: "Opus", description: "" }],
      configOptions: [],
      agents: [],
      currentAgent: "default",
      settingsManager: { dispose: vi.fn() },
      contextWindowSize: 1000000,
      lastReportedUsedTokens: 123456,
      toolUseCache: {},
      emittedToolCalls: new Set(),
      messageIdToUuid: new Map(),
      query: { supportedCommands: async () => [], close: vi.fn(), setPermissionMode: vi.fn() },
      input: { push: vi.fn(), end: vi.fn() },
    } as any;

    await agent.resumeSession({ sessionId: "watch-1", cwd: "/test", mcpServers: [] } as any);

    const registerSpy = bridge.registerSession as unknown as ReturnType<typeof vi.fn>;
    const call = registerSpy.mock.calls.find((c: any[]) => c[0].sessionId === "watch-1")![0];
    expect(call.model).toBe("Opus");
    expect(call.mode).toBe("acceptEdits");
    expect(call.contextUsed).toBe(123456);
    expect(call.contextSize).toBe(1000000);
    expect(call.detached).toBeUndefined();
  });
});

describe("noteSessionMeta fires on model/mode changes", () => {
  const SESSION_ID = "meta-session";
  let bridge: ReturnType<typeof makeFakeBridge>;
  let agent: ClaudeAcpAgentType;
  let setModelSpy: ReturnType<typeof vi.fn>;
  let setPermissionModeSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    bridge = makeFakeBridge();
    agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);
    setModelSpy = vi.fn();
    setPermissionModeSpy = vi.fn();
    const availableModes = [
      { id: "default", name: "Manual", description: "" },
      { id: "plan", name: "Plan Mode", description: "" },
      { id: "acceptEdits", name: "Accept Edits", description: "" },
      { id: "auto", name: "Auto", description: "" },
    ];
    agent.sessions[SESSION_ID] = {
      sessionId: SESSION_ID,
      cwd: "/test",
      cancelled: false,
      modes: { currentModeId: "default", availableModes },
      models: {
        currentModelId: "opus",
        availableModels: [
          { modelId: "opus", name: "Opus" },
          { modelId: "sonnet", name: "Sonnet" },
        ],
      },
      // Only "opus" supports auto — switching to "sonnet" invalidates it.
      modelInfos: [
        { value: "opus", displayName: "Opus", description: "", supportsAutoMode: true },
        { value: "sonnet", displayName: "Sonnet", description: "" },
      ],
      configOptions: [
        {
          id: "mode",
          name: "Mode",
          category: "mode",
          type: "select",
          currentValue: "default",
          options: availableModes.map((m) => ({ value: m.id, name: m.name })),
        },
        {
          id: "model",
          name: "Model",
          category: "model",
          type: "select",
          currentValue: "opus",
          options: [
            { value: "opus", name: "Opus" },
            { value: "sonnet", name: "Sonnet" },
          ],
        },
      ],
      agents: [],
      currentAgent: "default",
      fastModeEnabled: false,
      settingsManager: {},
      contextWindowSize: 200000,
      contextWindowAuthoritative: false,
      lastReportedUsedTokens: 0,
      providerCacheKey: "default",
      toolUseCache: {},
      emittedToolCalls: new Set(),
      query: makeMockQuery({
        setModel: setModelSpy,
        setPermissionMode: setPermissionModeSpy,
      }),
    } as any;
  });

  it("notes the mode id on a set_config_option mode change", async () => {
    await agent.setSessionConfigOption({ sessionId: SESSION_ID, configId: "mode", value: "plan" });
    expect(bridge.noteSessionMeta).toHaveBeenCalledWith(SESSION_ID, { mode: "plan" });
  });

  it("notes the mode id on a session/set_mode change (same choke point)", async () => {
    await agent.setSessionMode({ sessionId: SESSION_ID, modeId: "acceptEdits" });
    expect(bridge.noteSessionMeta).toHaveBeenCalledWith(SESSION_ID, { mode: "acceptEdits" });
  });

  it("notes the model DISPLAY name on a model change", async () => {
    await agent.setSessionConfigOption({
      sessionId: SESSION_ID,
      configId: "model",
      value: "sonnet",
    });
    expect(setModelSpy).toHaveBeenCalledWith("sonnet");
    expect(bridge.noteSessionMeta).toHaveBeenCalledWith(
      SESSION_ID,
      expect.objectContaining({ model: "Sonnet" }),
    );
  });

  it("stays silent for a re-asserted (unchanged) model", async () => {
    await agent.setSessionConfigOption({ sessionId: SESSION_ID, configId: "model", value: "opus" });
    expect(bridge.noteSessionMeta).not.toHaveBeenCalled();
  });

  it("notes the clamped mode when a model switch invalidates the current one", async () => {
    agent.sessions[SESSION_ID].modes.currentModeId = "auto";
    await agent.setSessionConfigOption({
      sessionId: SESSION_ID,
      configId: "model",
      value: "sonnet",
    });
    // The clamp is a real mode change that bypasses the MODE branch, so it
    // must note its own replay state alongside the model's.
    expect(bridge.noteSessionMeta).toHaveBeenCalledWith(
      SESSION_ID,
      expect.objectContaining({ model: "Sonnet" }),
    );
    expect(bridge.noteSessionMeta).toHaveBeenCalledWith(SESSION_ID, { mode: "default" });
    expect(setPermissionModeSpy).toHaveBeenCalledWith("default");
  });
});
