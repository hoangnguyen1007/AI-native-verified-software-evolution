# M7.2 — Windows console rendering investigation

Date: 2026-10-15. Question: how can the approved scrollback CLI present colored Unicode reliably in CMD, while preserving machine output and existing terminal settings?

## Primary evidence

| Observation | Evidence and limit |
|---|---|
| **CONFIRMED:** Google documents Antigravity CLI as a terminal interface, including CMD installation. | [Official overview](https://antigravity.google/docs/cli/overview), [official repository](https://github.com/google-antigravity/antigravity-cli/tree/89081f72e10a98c3689f34b1af7261f43136ab5d). At inspected commit `89081f72e10a98c3689f34b1af7261f43136ab5d`, the complete tree contains 15 files: docs, issue templates, media and status/title examples. Its renderer implementation is not published in that tree. **OPEN QUESTION:** AGY's exact private rendering stack; no claim that it uses Ink is made. |
| **CONFIRMED:** Gemini CLI has an explicit UI/rendering layer and terminal-aware logo layout. | At inspected commit `06d329873703ddabe8a842d962342ac4ad779e83`, [CLI dependencies](https://github.com/google-gemini/gemini-cli/blob/06d329873703ddabe8a842d962342ac4ad779e83/packages/cli/package.json) include React, Ink, gradient and string-width helpers. [Header](https://github.com/google-gemini/gemini-cli/blob/06d329873703ddabe8a842d962342ac4ad779e83/packages/cli/src/ui/components/Header.tsx) chooses long/short/tiny art using terminal width; [ThemedGradient](https://github.com/google-gemini/gemini-cli/blob/06d329873703ddabe8a842d962342ac4ad779e83/packages/cli/src/ui/components/ThemedGradient.tsx) has gradient/accent fallbacks; [theme manager](https://github.com/google-gemini/gemini-cli/blob/06d329873703ddabe8a842d962342ac4ad779e83/packages/cli/src/ui/themes/theme-manager.ts) honors `NO_COLOR`. These are directly inspected Gemini facts, not proof of AGY internals. |
| **CONFIRMED:** Windows output modes govern ANSI interpretation independently of UTF-8. | [Microsoft SetConsoleMode](https://learn.microsoft.com/windows/console/setconsolemode) requires `ENABLE_VIRTUAL_TERMINAL_PROCESSING` for VT parsing and recommends processed output. Changing a code page alone does not enable this flag. |
| **CONFIRMED:** Jansi provides a Java native bridge for this console boundary. | Pinned [Jansi 2.4.2 console implementation](https://github.com/fusesource/jansi/blob/jansi-2.4.2/src/main/java/org/fusesource/jansi/AnsiConsole.java) probes/enables/restores Windows modes and reads window size through [Kernel32 bindings](https://github.com/fusesource/jansi/blob/jansi-2.4.2/src/main/java/org/fusesource/jansi/internal/Kernel32.java). No downloaded installer/example was executed. |

## Diagnosis and adopted repair

**CONFIRMED by reproduction:** the original `EvoMain` wrote raw RGB SGR through UTF-8 streams; `evo.cmd` changed code pages but did not prepare the Windows output mode. A hidden native console with VT off reproduced the owner's symptom before the repair: `standalone: raw ANSI printed into console screen`. Prior ConPTY/PNG checks were insufficient for this case.

**PROVISIONAL routine adapter choice, now boundedly verified:** keep the Java/Picocli presentation and approved scrollback behavior, adding `TerminalSession` over pinned Jansi native calls. This applies the terminal-capability/layout separation observed in mature CLI rendering, without claiming to reproduce AGY's private implementation. The session sets and reads back processed-output/VT flags, prepares UTF-8 output, supplies actual startup window width and restores original state. Unsupported/native-failure paths remain readable plain text, including forced-color requests. Explicitly colored redirected streams remain available. No canonical stream wrapper, registry change or persistent terminal preference is introduced.

Alternatives considered: launcher-only setup would miss standalone JAR execution and couple runtime behavior to a shell; global console registry changes would affect other applications; replacing the approved Java scrollback adapter with a React/Ink application would add another runtime and broader input/render lifecycle work without being necessary to fix this defect. Replace the line-input/render boundary later if validated history, completion or live-resize requirements justify that scope.

## Bounded verification

The standard-library Python test [windows_console_smoke.py](../../cli/src/test/python/windows_console_smoke.py) starts an isolated hidden native console, explicitly disables VT, runs trusted platform artifacts and reads actual Unicode screen cells and foreground attributes. It does not treat an ANSI transcript or a drawn preview as successful native rendering. On the current Windows build **26100**:

- Standalone JAR starting with CP850 and VT off: UTF-8 Vietnamese and at least three distinct logo foreground attributes; no literal SGR text; original output mode and code pages restored.
- CMD launcher with VT initially off: same visible-output checks and restored state. The test measures a no-Java CMD control because this host's CMD enables VT itself on startup; that shell-owned mode is distinguished from the application's restoration obligation.
- CMD automatic width: a 48-column native window overrides deliberately stale `COLUMNS=120`; no row exceeds the window.
- Interactive CMD palette: real console input handles and keyboard records switch Vietnamese to English and run `quit`; prompt, both languages and restoration are checked in the actual screen buffer.
- Redirected forced-color JSON: valid JSON, no ANSI and empty stderr.

All **5 process cases PASS** after the repair. Seven Java tests cover native rejection, ignored mode changes, unavailable JNI, exact/idempotent restoration, retained mode bits, redirected JSON/color and explicit/native width. The complete CLI set is **37 tests / 5 suites**, zero failures/errors/skips, and packaging passes. Focused Java verification took **5.63 seconds** end-to-end, exceeding the five-second inner-loop target; the native process checks take about **1.87 seconds**. Ordinary Surefire output remains under `cli/target/`; no reproducibility package or raw screen dump is created.

Limits: current-host native console evidence, not a Windows-version/font/terminal matrix. Foreground-attribute checks establish actual color interpretation, not exact RGB colorimetry. Hard process termination can prevent restoration; normal exit and JVM shutdown are covered by the implementation. Theme preference approval remains distinct from usability acceptance; milestone/gate state is unchanged.

The extended native palette test also exercises `-n 1` and `next`. Scripted key events use [Microsoft VkKeyScanW](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-vkkeyscanw) from User32 to derive virtual keys/modifiers; Unicode code points are not virtual-key codes (45 would inject Insert instead of the hyphen). Final native assertions inspect the resulting pages and screen cells.
