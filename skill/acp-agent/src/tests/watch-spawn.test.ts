// claude-watch: watch-spawned (detached) sessions + the desk pickup (S1 of the
// "born in Zed" spawn feature). Covers the four load-bearing behaviors:
//
//   1. `detached` is fork bookkeeping and must NEVER leak into the SDK query
//      options (the silent-upstream-drift trap: `creationOpts` is spread into
//      `Options`).
//   2. `guardDetachedClient` suppresses every editor-bound call for a detached
//      session while the bridge tee (composed OUTSIDE the guard) keeps
//      mirroring to the watch — and stops suppressing the moment the session
//      is attached.
//   3. A detached session's permission request is wrist-only: the editor is
//      never asked, the wrist decision wins, a turn cancel settles it.
//   4. `session/new` adopts a pending watch session (live → in place, dead →
//      resume from disk, gone → forfeit the claim and fall through), and
//      `session/load` adopts a live detached session regardless of
//      fingerprint instead of tearing it down.
import { describe, it, expect, beforeEach, vi } from "vitest";
import type { Options } from "@anthropic-ai/claude-agent-sdk";
import type { AcpClient, ClaudeAcpAgent as ClaudeAcpAgentType } from "../acp-agent.js";
import type { BridgeChannel } from "../bridge-channel.js";
import { guardDetachedClient, teeClientToBridge } from "../bridge-channel.js";

let capturedOptions: Options | undefined;
const getSessionMessagesMock = vi.fn(async (_sessionId: string): Promise<unknown[]> => []);
// The store settles AFTER a short wrist turn ends (the CLI flushes the
// transcript late), which is why adoption re-polls the title; tests model the
// settled state.
const getSessionInfoMock = vi.fn(async (_sessionId: string, _opts?: unknown) => ({
  summary: "hello from the wrist",
  lastModified: 1700000000000,
}));
vi.mock("@anthropic-ai/claude-agent-sdk", async () => {
  const actual = await vi.importActual<typeof import("@anthropic-ai/claude-agent-sdk")>(
    "@anthropic-ai/claude-agent-sdk",
  );
  const { makeMockQuery, DEFAULT_CONTEXT_USAGE } = await import("./helpers.js");
  return {
    ...actual,
    getSessionMessages: getSessionMessagesMock,
    getSessionInfo: getSessionInfoMock,
    query: (args: { prompt: unknown; options: Options }) => {
      capturedOptions = args.options;
      return makeMockQuery({
        initializationResult: async () => {
          // A resume of a session whose store file never existed (spawned but
          // never prompted) fails exactly like the SDK does.
          if (args.options.resume === "vanished-session") {
            throw new Error("No conversation found with session ID: vanished-session");
          }
          return {
            models: [
              {
                value: "claude-sonnet-4-6",
                displayName: "Claude Sonnet",
                description: "Fast",
                supportsAutoMode: true,
              },
            ],
          };
        },
        getContextUsage: async () => DEFAULT_CONTEXT_USAGE,
      });
    },
  };
});

vi.mock("../tools.js", async () => {
  const actual = await vi.importActual<typeof import("../tools.js")>("../tools.js");
  return { ...actual, registerHookCallback: vi.fn() };
});

function makeMockClient(): AcpClient & Record<string, ReturnType<typeof vi.fn>> {
  return {
    sessionUpdate: vi.fn(async () => {}),
    requestPermission: vi.fn(async () => ({ outcome: { outcome: "cancelled" } })),
    readTextFile: vi.fn(async () => ({ content: "" })),
    writeTextFile: vi.fn(async () => ({})),
    unstable_createElicitation: vi.fn(async () => ({ action: "accept", content: {} })),
    unstable_completeElicitation: vi.fn(async () => {}),
    extNotification: vi.fn(async () => {}),
  } as unknown as AcpClient & Record<string, ReturnType<typeof vi.fn>>;
}

function makeFakeBridge(
  overrides: Partial<Record<string, unknown>> = {},
): BridgeChannel & Record<string, ReturnType<typeof vi.fn>> {
  return {
    registerSession: vi.fn(),
    deregisterSession: vi.fn(),
    forwardSessionUpdate: vi.fn(),
    forwardPermissionRequest: vi.fn(),
    forwardPermissionResolved: vi.fn(),
    forwardTurnBoundary: vi.fn(),
    noteSessionTitle: vi.fn(),
    onInject: vi.fn(),
    onPermissionDecision: vi.fn(),
    takePendingPickup: vi.fn(async () => null),
    start: vi.fn(),
    stop: vi.fn(),
    ...overrides,
  } as unknown as BridgeChannel & Record<string, ReturnType<typeof vi.fn>>;
}

/** Minimal live Session record for paths that read it directly (adoption,
 *  pickup, permissions). Only the fields those paths touch. */
function fakeSession(overrides: Record<string, unknown> = {}) {
  return {
    sessionId: "watch-1",
    cancelled: false,
    cwd: "/test",
    sessionFingerprint: JSON.stringify({ cwd: "/test", mcpServers: [] }),
    modes: { currentModeId: "default", availableModes: [] },
    models: { currentModelId: "default", availableModels: [] },
    modelInfos: [],
    configOptions: [{ id: "probe-option" }],
    agents: [],
    currentAgent: "default",
    settingsManager: { dispose: vi.fn() },
    accumulatedUsage: { inputTokens: 0, outputTokens: 0, cachedReadTokens: 0, cachedWriteTokens: 0 },
    abortController: new AbortController(),
    emitRawSDKMessages: false,
    contextWindowSize: 200000,
    contextWindowAuthoritative: false,
    providerCacheKey: "default",
    taskState: new Map(),
    toolUseCache: {},
    emittedToolCalls: new Set(),
    liveBackgroundTasks: new Map(),
    emittedAssistantText: false,
    owedTrailingIdles: 0,
    messageIdToUuid: new Map(),
    query: { supportedCommands: async () => [], close: vi.fn(), setPermissionMode: vi.fn() },
    input: { push: vi.fn(), end: vi.fn() },
    detached: true,
    ...overrides,
  } as any;
}

let ClaudeAcpAgent: typeof ClaudeAcpAgentType;

beforeEach(async () => {
  capturedOptions = undefined;
  getSessionMessagesMock.mockClear();
  getSessionMessagesMock.mockResolvedValue([]);
  vi.resetModules();
  const mod = await import("../acp-agent.js");
  ClaudeAcpAgent = mod.ClaudeAcpAgent;
});

describe("spawnDetachedSession (watch spawn)", () => {
  it("creates a working session whose options carry NO detached key and a pinned sessionId", async () => {
    const bridge = makeFakeBridge();
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);

    const response = await agent.spawnDetachedSession({ cwd: process.cwd() });

    // The spread-leak trap: `detached` must be split off before creationOpts
    // reaches the SDK Options.
    expect("detached" in capturedOptions!).toBe(false);
    // Fresh (non-resume) creation pins the SDK session id to the ACP id.
    expect(capturedOptions!.sessionId).toBe(response.sessionId);
    expect(agent.isSessionDetached(response.sessionId)).toBe(true);
  });

  it("registers with the bridge as detached", async () => {
    const bridge = makeFakeBridge();
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);

    const response = await agent.spawnDetachedSession({ cwd: process.cwd() });

    expect(bridge.registerSession).toHaveBeenCalledWith(
      expect.objectContaining({ sessionId: response.sessionId, detached: true }),
    );
  });

  it("a normal newSession is NOT detached and registers without the flag", async () => {
    const bridge = makeFakeBridge();
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);

    const response = await agent.newSession({ cwd: process.cwd(), mcpServers: [] });

    expect(agent.isSessionDetached(response.sessionId)).toBe(false);
    const registerSpy = bridge.registerSession as unknown as ReturnType<typeof vi.fn>;
    const call = registerSpy.mock.calls.find((c: any[]) => c[0].sessionId === response.sessionId);
    expect(call![0].detached).toBeUndefined();
  });
});

describe("guardDetachedClient", () => {
  const detachedIds = new Set(["watch-1"]);
  const isDetached = (id: string) => detachedIds.has(id);

  beforeEach(() => {
    detachedIds.clear();
    detachedIds.add("watch-1");
  });

  it("drops sessionUpdate for a detached session, passes it for others", async () => {
    const inner = makeMockClient();
    const guarded = guardDetachedClient(inner, isDetached);

    await guarded.sessionUpdate({ sessionId: "watch-1", update: {} } as any);
    await guarded.sessionUpdate({ sessionId: "zed-1", update: {} } as any);

    const updateSpy = inner.sessionUpdate as unknown as ReturnType<typeof vi.fn>;
    expect(updateSpy).toHaveBeenCalledTimes(1);
    expect(updateSpy.mock.calls[0][0].sessionId).toBe("zed-1");
  });

  it("stops suppressing the moment the session is no longer detached (adoption)", async () => {
    const inner = makeMockClient();
    const guarded = guardDetachedClient(inner, isDetached);

    await guarded.sessionUpdate({ sessionId: "watch-1", update: {} } as any);
    detachedIds.delete("watch-1"); // adopted
    await guarded.sessionUpdate({ sessionId: "watch-1", update: {} } as any);

    expect(inner.sessionUpdate).toHaveBeenCalledTimes(1);
  });

  it("never sends a detached session's requestPermission to the editor; settles cancelled on turn cancel", async () => {
    const inner = makeMockClient();
    const guarded = guardDetachedClient(inner, isDetached);
    const abort = new AbortController();

    const pending = guarded.requestPermission(
      { sessionId: "watch-1", toolCall: { toolCallId: "tc-1" }, options: [] } as any,
      abort.signal,
    );
    expect(inner.requestPermission).not.toHaveBeenCalled();

    abort.abort();
    await expect(pending).resolves.toEqual({ outcome: { outcome: "cancelled" } });
  });

  it("settles an already-aborted signal immediately", async () => {
    const guarded = guardDetachedClient(makeMockClient(), isDetached);
    const abort = new AbortController();
    abort.abort();

    await expect(
      guarded.requestPermission(
        { sessionId: "watch-1", toolCall: { toolCallId: "tc-1" }, options: [] } as any,
        abort.signal,
      ),
    ).resolves.toEqual({ outcome: { outcome: "cancelled" } });
  });

  it("rejects session-scoped elicitations and fs calls for a detached session", async () => {
    const inner = makeMockClient();
    const guarded = guardDetachedClient(inner, isDetached);

    await expect(
      guarded.unstable_createElicitation({ sessionId: "watch-1", mode: "form" } as any),
    ).rejects.toThrow(/detached/);
    await expect(guarded.readTextFile({ sessionId: "watch-1", path: "/x" } as any)).rejects.toThrow(
      /detached/,
    );
    await expect(
      guarded.writeTextFile({ sessionId: "watch-1", path: "/x", content: "" } as any),
    ).rejects.toThrow(/detached/);
    expect(inner.unstable_createElicitation).not.toHaveBeenCalled();
    expect(inner.readTextFile).not.toHaveBeenCalled();
    expect(inner.writeTextFile).not.toHaveBeenCalled();
  });

  it("drops extNotification carrying a detached sessionId, passes others through", async () => {
    const inner = makeMockClient();
    const guarded = guardDetachedClient(inner, isDetached);

    await guarded.extNotification("_claude/sdkMessage", { sessionId: "watch-1" });
    await guarded.extNotification("_claude/sdkMessage", { sessionId: "zed-1" });
    await guarded.extNotification("_claude/global", {});

    expect(inner.extNotification).toHaveBeenCalledTimes(2);
  });

  it("tee OUTSIDE, guard INSIDE: the bridge mirror keeps flowing while the editor leg is dropped", async () => {
    const inner = makeMockClient();
    const bridge = makeFakeBridge();
    const client = teeClientToBridge(guardDetachedClient(inner, isDetached), bridge);
    const notification = { sessionId: "watch-1", update: { sessionUpdate: "agent_message_chunk" } };

    await client.sessionUpdate(notification as any);

    expect(bridge.forwardSessionUpdate).toHaveBeenCalledWith(notification);
    expect(inner.sessionUpdate).not.toHaveBeenCalled();
  });
});

describe("wrist-only permissions for a detached session", () => {
  it("the wrist decision resolves the permission; the editor is never asked", async () => {
    let wristNotify: ((d: any) => void) | undefined;
    const inner = makeMockClient();
    const bridge = makeFakeBridge({
      onPermissionDecision: vi.fn((h: (d: any) => void) => {
        wristNotify = h;
      }),
    });
    // Same self-referencing wiring shape as runAcp: the guard's predicate
    // closes over `agent`, which is assigned before any call can flow.
    let agent: ClaudeAcpAgentType;
    agent = new ClaudeAcpAgent(
      teeClientToBridge(
        guardDetachedClient(inner, (id) => (agent ? agent.isSessionDetached(id) : false)),
        bridge,
      ),
      { log: () => {}, error: () => {} },
      bridge,
    );
    agent.sessions["watch-1"] = fakeSession();

    const pending = agent.canUseTool("watch-1")("Bash", { command: "ls" }, {
      signal: new AbortController().signal,
      suggestions: [],
      toolUseID: "tc-9",
    } as any);

    // The permission surfaced to the watch (via the tee)…
    await vi.waitFor(() => expect(bridge.forwardPermissionRequest).toHaveBeenCalled());
    // …and never to the editor.
    expect(inner.requestPermission).not.toHaveBeenCalled();

    wristNotify!({ sessionId: "watch-1", toolCallId: "tc-9", optionId: "allow", behavior: "allow" });
    const result = await pending;
    expect(result?.behavior).toBe("allow");
  });
});

describe("session/new desk pickup", () => {
  it("adopts a live detached session in place: same record, attached, re-registered without the flag", async () => {
    const bridge = makeFakeBridge({ takePendingPickup: vi.fn(async () => "watch-1") });
    const client = makeMockClient();
    const agent = new ClaudeAcpAgent(client, { log: () => {}, error: () => {} }, bridge);
    const live = fakeSession();
    agent.sessions["watch-1"] = live;

    const response = await agent.newSession({ cwd: "/test", mcpServers: [] });

    expect(response.sessionId).toBe("watch-1");
    expect(response.configOptions).toBe(live.configOptions); // the SAME session, not a copy
    expect(agent.sessions["watch-1"]).toBe(live);
    expect(live.detached).toBe(false);
    expect(bridge.registerSession).toHaveBeenCalledWith(
      expect.not.objectContaining({ detached: true }),
    );
    // No fresh SDK session was created.
    expect(capturedOptions).toBeUndefined();

    // The deferred half: history replay, the banner, available commands, and
    // the post-adoption title fetch (the wrist turn's polls raced the CLI's
    // transcript flush and lost — pickup must re-ask).
    const updateSpy = client.sessionUpdate as unknown as ReturnType<typeof vi.fn>;
    await vi.waitFor(() => {
      expect(getSessionMessagesMock).toHaveBeenCalledWith("watch-1");
      const texts = updateSpy.mock.calls.map((c: any[]) => c[0]?.update?.content?.text ?? "");
      expect(texts.some((t: string) => t.includes("Continued from your watch"))).toBe(true);
      const titles = updateSpy.mock.calls
        .filter((c: any[]) => c[0]?.update?.sessionUpdate === "session_info_update")
        .map((c: any[]) => c[0].update.title);
      expect(titles).toContain("hello from the wrist");
    });
    expect(bridge.noteSessionTitle).toHaveBeenCalledWith("watch-1", "hello from the wrist");
  });

  it("adopts despite a fingerprint mismatch (editor MCP servers must not kill the live session)", async () => {
    const bridge = makeFakeBridge({ takePendingPickup: vi.fn(async () => "watch-1") });
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);
    const live = fakeSession();
    agent.sessions["watch-1"] = live;

    const response = await agent.newSession({
      cwd: "/test",
      mcpServers: [{ name: "zed-mcp", command: "node", args: [], env: [] } as any],
    });

    expect(response.sessionId).toBe("watch-1");
    expect(live.settingsManager.dispose).not.toHaveBeenCalled(); // no teardown
    expect(live.detached).toBe(false);
  });

  it("resumes a dead pending session from disk under the same id", async () => {
    const bridge = makeFakeBridge({ takePendingPickup: vi.fn(async () => "watch-2") });
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);
    // Not in agent.sessions at all: the fork restarted since the spawn.

    const response = await agent.newSession({ cwd: process.cwd(), mcpServers: [] });

    expect(response.sessionId).toBe("watch-2");
    expect(capturedOptions!.resume).toBe("watch-2");
  });

  it("forfeits the claim and falls through to a fresh session when the store file is gone", async () => {
    const bridge = makeFakeBridge({ takePendingPickup: vi.fn(async () => "vanished-session") });
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);

    const response = await agent.newSession({ cwd: process.cwd(), mcpServers: [] });

    // A fresh session, not the vanished one, and no error surfaced to the user.
    expect(response.sessionId).not.toBe("vanished-session");
    expect(capturedOptions!.resume).toBeUndefined();
  });

  it("never hands one session to two threads: an already-attached pickup is ignored", async () => {
    const bridge = makeFakeBridge({ takePendingPickup: vi.fn(async () => "watch-1") });
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);
    agent.sessions["watch-1"] = fakeSession({ detached: false }); // adopted via load earlier

    const response = await agent.newSession({ cwd: process.cwd(), mcpServers: [] });

    expect(response.sessionId).not.toBe("watch-1");
    expect(capturedOptions).toBeDefined(); // a fresh session was created
  });

  it("no pending pickup → the normal path, untouched", async () => {
    const bridge = makeFakeBridge(); // takePendingPickup resolves null
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);

    const response = await agent.newSession({ cwd: process.cwd(), mcpServers: [] });

    expect(response.sessionId).toBeDefined();
    expect(capturedOptions).toBeDefined();
  });

  it("a broken bridge pickup degrades to the normal path instead of failing session/new", async () => {
    const bridge = makeFakeBridge({
      takePendingPickup: vi.fn(async () => {
        throw new Error("bridge exploded");
      }),
    });
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);

    const response = await agent.newSession({ cwd: process.cwd(), mcpServers: [] });

    expect(response.sessionId).toBeDefined();
  });
});

describe("session load adopts a live detached session", () => {
  it("resumeSession returns the live session (no teardown) and attaches it, fingerprint mismatch included", async () => {
    const bridge = makeFakeBridge();
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);
    const live = fakeSession();
    agent.sessions["watch-1"] = live;

    const response = (await agent.resumeSession({
      sessionId: "watch-1",
      cwd: "/test",
      mcpServers: [{ name: "zed-mcp", command: "node", args: [], env: [] } as any],
    } as any)) as { sessionId?: string };

    expect(response.sessionId).toBe("watch-1");
    expect(agent.sessions["watch-1"]).toBe(live);
    expect(live.detached).toBe(false);
    expect(live.settingsManager.dispose).not.toHaveBeenCalled();
    expect(capturedOptions).toBeUndefined(); // no second SDK query — the #69 lesson
    expect(bridge.registerSession).toHaveBeenCalledWith(
      expect.objectContaining({ sessionId: "watch-1" }),
    );
  });

  it("a QUERY-CLOSED detached husk still goes through the normal teardown+resume path", async () => {
    const bridge = makeFakeBridge();
    const agent = new ClaudeAcpAgent(makeMockClient(), { log: () => {}, error: () => {} }, bridge);
    agent.sessions["watch-2"] = fakeSession({
      sessionId: "watch-2",
      queryClosed: true,
      cwd: process.cwd(),
      sessionFingerprint: JSON.stringify({ cwd: process.cwd(), mcpServers: [] }),
    });

    const response = (await agent.resumeSession({
      sessionId: "watch-2",
      cwd: process.cwd(),
      mcpServers: [{ name: "zed-mcp", command: "node", args: [], env: [] } as any],
    } as any)) as { sessionId?: string };

    // The husk was torn down and the session resumed from disk.
    expect(response.sessionId).toBe("watch-2");
    expect(capturedOptions!.resume).toBe("watch-2");
  });
});
