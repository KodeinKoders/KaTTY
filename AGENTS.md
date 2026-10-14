# AGENTS.md

Guidance for AI agents working on KaTTY, a terminal emulator written in Kotlin with Compose for Desktop.
`README.adoc` describes the project for humans, this file is about how to work on it.

## What it is

KaTTY runs the user's login shell in a terminal drawn on a Compose `Canvas`.
The emulation is done by [libghostty-vt](https://ghostty.org) (called through Java FFM, not JNI or JNA), the pseudo-terminal by [pty4j](https://github.com/JetBrains/pty4j).
It only runs on macOS on Apple Silicon for now: that is the only platform libghostty-vt is built for.
The build needs JDK 25 (Gradle downloads it), because FFM is final there.

## Modules

`app` → `terminal-compose` → `terminal` → `ghostty-vt` → `libghostty-vt/` (a prebuilt dylib and its headers, not a Gradle module).
`app` also uses `terminal-themes` → `terminal`. `iterm2-color-schemes/` holds the color schemes it packs (not a Gradle module either).

| Module | Package | Role |
| --- | --- | --- |
| `app` | `app.katty` | The window. `Shell` starts a `TerminalSession` and shows a `Terminal`. |
| `terminal-compose` | `app.katty.terminal.compose` | `Terminal`, the composable. Draws frames with the bundled JetBrains Mono, translates key events. |
| `terminal-themes` | `app.katty.terminal.themes` | `ITermColorSchemes`: the 811 `.itermcolors` schemes as `TerminalTheme`s, parsed on demand with kotlinx.serialization and xmlutil. The files and an `index.txt` of their names are copied into the resources by `prepareColorSchemes`. |
| `terminal` | `app.katty.terminal` | `TerminalSession`: a program in a PTY, its output fed to a terminal, published as immutable `TerminalFrame`s. Has its own key, frame and theme model. |
| `ghostty-vt` | `app.katty.ghostty` | Kotlin API over the jextract-generated FFM bindings (`…ghostty.ffm`, Java, committed). |

**Nothing above `terminal` mentions `ghostty-vt`**: `terminal` converts in `Mapping.kt`, and `ghostty-vt` is only an `implementation` dependency of it.
Keep it that way.
The generated Java is never part of an API: no `MemorySegment` or generated type leaves `ghostty-vt`.

## Commands

```shell
./gradlew build            # everything, with the tests: what CI runs
./gradlew :app:run         # opens the window
./gradlew :app:hotRun      # opens it with Compose Hot Reload, which the MCP server needs
./gradlew :terminal:test   # one module
```

`ghostty-vt/jextract.sh` regenerates the bindings and the key enums (`GhosttyKey`, and `TerminalKey` of `terminal`) after libghostty-vt is updated.
It needs jextract for JDK 25 on the `PATH`, or in `$JEXTRACT`.
`libghostty-vt/README.adoc` has the update procedure and says which Ghostty commit the dylib comes from.

## Build conventions

The Gradle build follows the Kodein conventions (the `kodein-gradle-kotlin-project` skill has them in full).
The ones that are easy to break:

- Kotlin DSL only. No coordinate in a build script: dependencies and plugins come from `gradle/libs.versions.toml`, and project dependencies use typed accessors (`projects.terminalCompose`).
- Every catalog plugin is also declared in the root `build.gradle.kts` with `apply false`. Catalog sections are sorted, with a blank line between domains, and each `[versions]` entry has a comment with its release page.
- Every module sets `jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())`. The version is in `gradle.properties`.
- Repositories are declared in `settings.gradle.kts` only.
- A line that is not obvious in a build script gets a comment saying why.

## Threads

- A `GhosttyTerminal` is **not thread-safe**. In a session, one lock guards it, and the key encoder that reads it.
- The `GhosttyRenderState` belongs to the frame producer thread: only its `update` reads the terminal, so only that is under the lock.
- Callbacks of the terminal run on the thread that wrote to it, under the lock: they must only enqueue, and an exception must never escape them (it would take the JVM down).
- The frame producer publishes at most 60 frames per second. A row that did not change is **the same object** in the next frame (the renderer's cache relies on it): do not break that by creating rows needlessly.

## Keyboard

- Printable text comes from AWT's `KEY_TYPED` events, so that dead keys, input methods and Option characters work. `KEY_PRESSED` is only used for keys without text and for Ctrl combinations.
- Compose's `KeyEvent` does **not** expose the AWT event: read it with Compose's public API (`Key.nativeKeyCode`, `utf16CodePoint`, `type`). Typed characters have the type `Unknown`.
- Option is not the program's Alt. Command shortcuts are not sent to the program.
- The text of a key event given to the encoder is the character as typed, before any Ctrl transformation (`"c"` for Ctrl+C). libghostty's encoder is not xterm's: Shift+Enter and Ctrl+I are distinct sequences even without the Kitty protocol.

## Tests

- The tests are real: they start programs in a PTY (`/bin/sh`, `seq`, `od`, `stty`), call the native library, and draw with Skia in memory. They wait on conditions with timeouts and never on fixed sleeps, except where time is what is tested.
- A test that waits reports the last screen when it times out: keep doing that.
- The rendering tests keep their images in `terminal-compose/build/renders`: look at them when you change how something is drawn.
- A new test should be shown to fail without the change it covers.

## Running the app

Starting the app opens a window on the user's desktop, and the user may be working in it or around it. So:

- **Never type into the app** with synthesized keystrokes (`osascript`, `Robot`, …): without a guarantee about the focused window they land in the user's other applications.
- **Never take a full-screen screenshot.** Capture the app's own window only: with the Compose Hot Reload MCP tool `take_screenshot`, or `screencapture -l <window id>`.
- **Several instances may run**, for instance one started from the IDE. Identify yours by process id, and stop only it.
- `./gradlew :app:hotRun` does not pass `--enable-native-access=ALL-UNNAMED`, which `:app:run` does: it prints JVM warnings at startup.

`.mcp.json` registers the Compose Hot Reload MCP server (`hotMcpServer`): the app must be started with `hotRun`, not `run`, for it to connect.
It is slow to start (`--no-daemon`), and its tools that work through semantics (`click`, `type_text`, `get_semantic_tree`) do nothing for the terminal, which is a `Canvas`.

## Pitfalls

- **Concurrent Gradle builds in the same directory corrupt each other**: IntelliJ, the MCP server and a hot-reloading app all build in `build/`. Missing class files, `FileNotFoundException` in the Kotlin compiler or a `NoClassDefFoundError` after a successful build mean that happened: `./gradlew :<module>:clean` and run again.
- A Compose composable keeps its focus when the OS window loses its own: use `LocalWindowInfo.current.isWindowFocused` as well to know whether the keys go to it.
- libghostty-vt flags the row that the cursor leaves as dirty, even if it did not change, and its cursor does not blink unless the program asks for it (`TerminalSession.start` turns the default on).
- libghostty-vt's render state only reads the default foreground and background when **both** are set, and keeps what it last read when they are cleared: to go back to the original colors, set them (white on black) instead of clearing them. `TerminalSession.setTheme(null)` does that.
- pty4j ignores the size in pixels: the program only gets the size in cells.
- The generated Java contains macOS system types: generating on another OS gives another file set.

## Working on this repository

- Commit only when asked. A commit message says what changed and why, and stands on its own: no reference to a plan step, an issue number, or to local files.
- `plan.adoc` (the implementation plan) and `ISSUES.adoc` (review findings) are local scratch files and are **gitignored**: never commit them. `.idea/` is ignored too.
- When a step of `plan.adoc` has been executed and committed, close it in the file: strike its heading through, move its text in a collapsed `.Step` block, and add a collapsed `.Done` block that says what was actually done, where it differs from the plan, and ends with the commit's subject and short hash.
- Documentation is AsciiDoc, one sentence per line (the `kodein-asciidoc-writing` skill has the rules). This file is Markdown because the name is a convention.
- Ask before taking an architecture or technology decision that the plan does not settle, and say what the options cost. Report what could not be verified (a real keyboard, another platform) instead of implying it was.
