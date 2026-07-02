// PTY spawn helpers. The bridge fakes a PTY with the system `script` utility,
// whose CLI differs between BSD/macOS and util-linux:
//   BSD/macOS:  script -q /dev/null <bin> <args...>       (argv form, no shell)
//   util-linux: script -qec "<command string>" /dev/null  (string form, run by $SHELL)
import { spawn as childSpawn } from "node:child_process";

export function shellQuote(parts) {
  return parts
    .map((p) => `'${String(p).replaceAll("'", `'\\''`)}'`)
    .join(" ");
}

export function buildPtySpawnArgs(bin, args = [], opts = {}) {
  const { platform = process.platform, cols = 120, rows = 40, userShell } = opts;
  if (platform === "linux") {
    // util-linux script has no argv form, and with piped stdin it leaves the
    // PTY winsize at 0x0 (COLUMNS/LINES env alone don't set the ioctl size),
    // so set it with stty before exec'ing the agent.
    const restoreShell = userShell ? `SHELL=${shellQuote([userShell])} ` : "";
    const cmd =
      `stty cols ${Math.floor(cols)} rows ${Math.floor(rows)} 2>/dev/null; ` +
      `${restoreShell}exec ${shellQuote([bin, ...args])}`;
    return ["-qec", cmd, "/dev/null"];
  }
  return ["-q", "/dev/null", bin, ...args];
}

export function spawnPtyProcess(bin, args, { cwd, env, cols, rows }) {
  const spawnEnv = { ...env };
  let userShell;
  if (process.platform === "linux") {
    // script -c hands the command string to $SHELL, which may not be POSIX
    // (csh/tcsh would misparse the quoting). Pin /bin/sh for the wrapper and
    // hand the user's shell back to the agent via the command string.
    userShell = spawnEnv.SHELL;
    spawnEnv.SHELL = "/bin/sh";
  }
  return childSpawn("script", buildPtySpawnArgs(bin, args, { cols, rows, userShell }), {
    cwd,
    env: spawnEnv,
    stdio: ["pipe", "pipe", "pipe"],
  });
}
