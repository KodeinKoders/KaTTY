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
- The producer waits for the output to pause (3 ms, at most 8) before it makes a frame. A program redraws in several writes that are read separately, and zsh moving the selection in its completion menu moves the cursor down to the menu, draws, and moves it back: a frame made after the first write shows the cursor in the menu for a frame. Do not make it publish at the first chunk of output.
- The frame producer publishes at most 60 frames per second. A row that did not change is **the same object** in the next frame (the renderer's cache relies on it): do not break that by creating rows needlessly.

## Keyboard and mouse

- Printable text comes from AWT's `KEY_TYPED` events, so that dead keys, input methods and Option characters work. `KEY_PRESSED` is only used for keys without text and for Ctrl combinations.
- While the terminal has the Compose focus it also has an input method session (`terminalTextInput`): that is what makes macOS report a dead key while it is composed, which is drawn underlined at the cursor (the preedit, kept in `TerminalTextInput`, drawn by `TerminalView`, never written to the terminal). The final character may then come as a `KEY_TYPED`, as committed text of the input method, or as both, depending on the platform: `TerminalTextInput.deliver` sends it **once**, so send text through it, not straight to the session. The `TerminalTextInput` DEBUG log tells which route delivered what.
- The session follows the **Compose** focus, not the window's: opening or closing one moves the AWT focus for a moment, which would close and open it again.
- An open session makes macOS show the accents menu when a letter is held: `app` registers `ApplePressAndHoldEnabled=false` at startup (`disablePressAndHold`, FFM calls to the Objective-C runtime). Any other program that shows the `Terminal` has to do the same.
- Compose's `KeyEvent` does **not** expose the AWT event: read it with Compose's public API (`Key.nativeKeyCode`, `utf16CodePoint`, `type`). Typed characters have the type `Unknown`.
- Option is not the program's Alt. Command shortcuts are not sent to the program. The exception is the **line editing keys of macOS** (`lineEditingText`, taken in `Terminal` before `KeyTranslator`): Option + Left / Right is `ESC b` / `ESC f`, Command + Left / Right / Backspace is `^A` / `^E` / `^U`, which is what Ghostty and iTerm2 send. Encoded as keys (`CSI 1;3 D`…) a shell does not know them and prints the end of the sequence. They go through `TerminalSession.sendText`, whatever keyboard mode the program is in, and with Shift or Control held they stay the program's.
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

- The mouse goes `Terminal` → `MouseTranslator` → `TerminalSession.sendMouse` → libghostty's mouse encoder, and only while the program asked for it (`TerminalFrame.mouseTracking`). **Positions are in cells (fractional) from `Terminal` to the session**, which converts them with the whole-number cell size it gave the terminal in `resize`: what a program is told of the size of a cell and what SGR-Pixels reports must agree, and a rounded float cell width drifts by dozens of pixels at the last column.
- **Selecting text** is the terminal's and the program is told nothing: with the left button when the program did not ask for the mouse, and with Shift + left button when it did (`SelectionDriver` decides, before `MouseTranslator` sees the event). The gesture is libghostty's (`GhosttySelectionGesture`: double click a word, triple click a line, Option+Command a block), installed in the terminal, which gives each row's selected cells to the render state; `TerminalFrame.selection` has them, outside the rows so that selecting does not change them. Selecting does **not** copy: the clipboard is the user's. The text of the last selection is kept in memory (`PrimarySelection`) for a **middle click**, which pastes it (when the program did not ask for the mouse, and with Shift when it did); it stays when the selection goes. Copy and paste are keys, handled before the program sees them: Command + C / V on macOS, Control + Shift + C / V elsewhere (`clipboardShortcut`; Control + C is the program's interrupt). A paste (key or middle click) goes through `TerminalSession.paste`, which follows the program's bracketed-paste mode and replaces the control bytes that could be taken for commands, and counts as typing (it unselects and snaps to the bottom). There is no confirmation for a multi-line paste into a program that did not ask for bracketed paste, which Ghostty has. What is typed for the program, and the program writing over selected cells (the session sees a row of the selection change), unselect: libghostty does neither. A press that was sent has its release sent whatever Shift does, and a gesture that started selecting finishes selecting. Shift on the wheel is not a bypass: it is how AWT says that the scroll is horizontal.
- The pointer (`terminalPointerIcon`): the arrow over the scrollbar (or while its thumb is dragged), else the cross when a drag would select a block (Option and Command held, and the left button selects), else the arrow while the program has the mouse, the I-beam with Shift held then or when it does not, or the shape the program asked for.
- A trackpad's wheel is fractions of a notch: they are added up, one report per whole notch, at most 10 per event.
- The pointer's shape follows what the program asked for (`TerminalFrame.pointerShape`, OSC 22; the text cursor by default).

- **The history** is scrolled by the session in whole rows (`scrollBy`, `scrollPage`, `scrollToTop`, `scrollToBottom`, `scrollToRow`, `scrollWheel`), and where the viewport is comes back in every frame as `TerminalFrame.scrollbar` (`null` when there is no history, which is also the case of the alternate screen). A notch of the wheel is 3 rows (`WheelScroller` adds the fractions of a trackpad up). With the mouse asked for the wheel is the program's; without it, it scrolls the history, or sends arrow keys on the alternate screen (alternate scroll, mode 1007). Shift + PageUp / PageDown / Home / End scroll it, and are consumed (the release too) only while there is history. Typing for the program snaps back to the bottom; output does not move the view.
- **The amount of a wheel event is normalized** (`WheelNormalizer`, in `Terminal.kt`'s pointer loop): macOS reports a slow click of a mouse wheel as 0.1 of a notch, which Java passes on as the precise rotation, so adding fractions up scrolled one click in three or four (one in ten with the mouse reported). A click is an event with a non-zero rounded rotation that comes alone (no scroll event in the 100 ms before): it is at least a notch, as Ghostty does it; a trackpad's stream keeps its fractions. `org.slf4j.simpleLogger.log.app.katty=debug` logs what the mouse gives.
- **The scrollbar** is drawn by `TerminalView` over the right edge, but its pointer is handled in `Terminal`'s own pointer loop (`ScrollbarInteraction`), not by a sibling composable, which would take the wheel events of its strip from the terminal. It fades out after a second.
- **Autoscroll while selecting:** a drag that is past the top or the bottom makes `selectionDrag` answer `UP` or `DOWN`, and `SelectionAutoscroll` ticks `selectionAutoscroll` every 50 ms with the last position. libghostty's tick scrolls the viewport itself: do not scroll in addition. A pointer that leaves with a button down is an `Exit` event: it is a drag that goes on.

## Images

- The **Kitty graphics protocol** is implemented by libghostty (parsing, chunks, zlib, raw RGB/RGBA/gray, placements, deletion, animation): KaTTY gives it what it asks of the embedder and draws what it keeps. The **PNG decoder** is a process-global setting (`ghostty_sys_set`) that `NativeLibrary` installs once with `javax.imageio` (it never throws into native code), and each terminal has a **storage limit** (`TerminalSession.start` gives 320 MB): without it nothing is stored.
- `TerminalSession.produceFrame` reads the placements for **every frame while the storage is not empty** (scrolling moves them with no change of the storage), copies an image's pixels once per generation under the lock, converts them to RGBA outside it, and keeps them in a cache that is pruned to the ids that have a placement. `TerminalFrame.images` has them in **cells** (fractional, positive or negative), not pixels, since libghostty sizes them with the whole-number cell size that the session gave it and the renderer measured another. A `TerminalImage` is the same object while it does not change: `ImageBitmapCache` keys on it.
- The renderer draws the layers (`z`) around the text: below the cell backgrounds, below the text, above the text, clipped to the terminal, with the edges on whole pixels.
- The session gives the terminal a cell size (8 × 16) at start: an image sent before the first `resize` would otherwise be placed on cells of no size.
- **chafa needs `-f kitty`**: it does not probe for Kitty graphics, it picks the format from `TERM` / `TERM_PROGRAM` and from the sixel flag of the device attributes answer, so with `TERM=xterm-256color` it draws characters. KaTTY does not change its identity for that, on purpose (Orca advertises Sixel, which libghostty does not have). Programs that query the terminal (`kitten icat`) work by themselves.
- An answer of the terminal (`ESC _ G i=1;OK ESC \`) is **input** of the program: a test whose program reads a key must use `q=2`, which turns the answers off, as chafa does.
- Not drawn: unicode placeholders (virtual placements), Sixel, iTerm2 inline images.

## What the host is told

`TerminalSession` is meant to be embedded in an application that has many terminals: besides `frames` and `exited` it publishes `title`, `workingDirectory` and `progress` (state flows, a `StateFlow` that has the last value) and `events` (`TerminalEvent`: `Bell`, `Notification`, `ClipboardWrite`, `PromptStarted`, `CommandStarted`, `CommandFinished`; a `SharedFlow` **without replay**, so a collector that comes late misses what happened before, and nobody collecting drops them). A frame has `alternateScreen`, and a cell `hyperlink`.

- libghostty calls back **synchronously, inside `vt_write`**, on the reader thread, under the lock (`GhosttyTerminal.onBell`, `onTitleChanged`, `onPwdChanged`, `onNotification`, `onProgress`, `onSemanticPrompt`, `onClipboardWrite`, `onReset`). They follow the rules of the threads: they only record or enqueue (`tryEmit` on a buffered `SharedFlow` that drops the oldest), and an exception never escapes (`guarded`). Strings that come with them are copies.
- **The title and the directory are not read in their callbacks**: libghostty says the title can be read after the callback returns. The callback sets a flag and `publishAfterWrite` reads `terminal.title` / `terminal.pwd` once `vtWrite` has returned, under the same lock. A reset (RIS) clears them without calling those callbacks, so `onReset` sets the same flags. `workingDirectory` is a path: `decodeWorkingDirectory` turns a `file://host/path` URI into it and removes the percent-encoding.
- **The clipboard** (OSC 52): a write is allowed by default and becomes `TerminalEvent.ClipboardWrite` (`start(clipboardWrite = false)` refuses it); `Terminal` puts it in the system clipboard, or in what a middle click pastes for the primary selection. A **read** is refused unless the host gives `start(clipboardReader = …)`, and it is the one callback that **blocks**: libghostty wants the answer before it returns, so the function runs on the reader thread and the program's output waits for it. Installing a reader also makes libghostty send the program paste events (mode 5522): `setClipboardReader` only installs it when there is one.
- **Shell integration** (`ShellIntegration`, on by default, `start(shellIntegration = false)` turns it off): a shell does not send OSC 7 (directory) and OSC 133 (prompts) on its own, so for zsh and bash started as a login or interactive shell the session makes them read KaTTY's scripts (`terminal/src/main/resources/app/katty/terminal/shell-integration`, copied once to a temporary directory) and then the user's own files. zsh: `ZDOTDIR` points to a `.zshenv` that puts it back (the user's is saved in `KATTY_ZDOTDIR`), reads the user's, and loads the integration. bash: `--rcfile` points to the script, which reads the files of a login shell, or `~/.bashrc`, and installs its hooks after them. **`--posix` with `ENV`, which Ghostty and kitty use, does not work: the bash 3.2 of macOS does not read `ENV` that way.** A prompt theme that rewrites `PS1` after the first prompt can lose the mark of the end of the prompt (B) for that prompt; the other marks do not depend on it. Any other shell, and a command with other arguments, is started as it is.
- **Prompt marks** are what `scrollToPrompt(delta)` and the keys Command + Up / Down (Control + Shift + Up / Down elsewhere, `promptJump`) use: they only work when the shell sends them, and, like the history keys, only while there is history (otherwise the program gets them).
- **Links** (`session.linkAt(column, row)`): one that a program marked (OSC 8, which libghostty gives per cell and as an address through a grid ref, with no way to get its `id=`) or one written in the row's text (`findUrl` in `TerminalLinks.kt`, which has no URL detection from libghostty: it ends at whitespace and drops the punctuation of a sentence and a closing bracket without its opening one; one that wraps onto the next row is not found). With Command held (Control elsewhere; `linkModifier`) `LinkInteraction` underlines the link under the pointer and shows the hand, and a left click on it opens it through `Terminal(onOpenLink)`, **before** the selection and the program's mouse see it: the program is told nothing of that click. The default opener only opens `http`, `https`, `ftp`, `file` and `mailto`.

### Focus, visibility and configuration

- **Focus reporting (mode 1004)**: `Terminal` calls `session.setFocused(active)` (`active` is "the keys go to this terminal": Compose focus and window focus), **after waiting 50 ms**: the effect is restarted when `active` changes, so that the focus moving for a moment while the input method session opens or closes tells the program nothing (vim would get a lost and a gained). The session only sends when the focus changes, and only if the program asked (`encodeFocus` checks the mode: `ghostty_focus_encode` does not); it starts not focused, so a program that turns the mode on is told nothing until the focus changes. It is not what is typed: it does not unselect nor scroll to the bottom.
- **`session.setVisible(false)`** stops the frame producer for a terminal that is not shown: the producer waits in its loop, the request that came meanwhile is kept, and showing the session again makes **one** frame. The terminal is still fed, keys, mouse and replies still go out, and title, directory, progress, events and exit are still published. `frames.value` and `linkAt` are stale while hidden, and a hidden session still holds its last frame and its images: it saves processor time, not memory (use `setScrollbackLimit` and `setImageStorageLimit`, which libghostty applies at once). There is no automatic pause on purpose: code that reads `frames.value` would see a stale frame forever, and a composable that stays composed does not collect less.

## Pitfalls

- **Concurrent Gradle builds in the same directory corrupt each other**: IntelliJ, the MCP server and a hot-reloading app all build in `build/`. Missing class files, `FileNotFoundException` in the Kotlin compiler or a `NoClassDefFoundError` after a successful build mean that happened: `./gradlew :<module>:clean` and run again.
- A Compose composable keeps its focus when the OS window loses its own: use `LocalWindowInfo.current.isWindowFocused` as well to know whether the keys go to it.
- libghostty-vt flags the row that the cursor leaves as dirty, even if it did not change, and its cursor does not blink unless the program asks for it (`TerminalSession.start` turns the default on).
- libghostty-vt's render state only reads the default foreground and background when **both** are set, and keeps what it last read when they are cleared: to go back to the original colors, set them (white on black) instead of clearing them. `TerminalSession.setTheme(null)` does that.
- A character that the terminal's font does not have is given to the system, which picks the **emoji** font for the ones that can be an emoji, so `⏸` (Claude Code's plan mode) was a blue picture. `TextFallback` finds a text font for the ones that are not emoji by default and are not followed by the variation selector 16 (`Character.isEmojiPresentation`). It asks the faces of the font in use (`TerminalFont.faces`), so it must not name a font: the font is going to be configurable, and any new way to make a `TerminalFont` has to give the Skia typefaces it loaded. Look for a text font, and do not accept a color one.
- pty4j ignores the size in pixels (its `WinSize` has only cells, and the pixel fields of the pty stay 0), so `TerminalSession.resize` sets the whole window size with its own `ioctl(TIOCSWINSZ)` (`PtyWindowSize.kt`, through FFM, with the **macOS** request number), and falls back to pty4j's when that fails. libghostty answers `CSI 14/16/18 t` and mode 2048 from the size given to `resize`, through a callback that reads copies of it, never the terminal. Programs that do not know the size of a cell (chafa, `kitten icat`…) assume a ratio and draw images squeezed.
- The generated Java contains macOS system types: generating on another OS gives another file set.

## Working on this repository

- Commit only when asked. A commit message says what changed and why, and stands on its own: no reference to a plan step, an issue number, or to local files.
- `plan.adoc` (the implementation plan) and `ISSUES.adoc` (review findings) are local scratch files and are **gitignored**: never commit them. `.idea/` is ignored too.
- When a step of `plan.adoc` has been executed and committed, close it in the file: strike its heading through, move its text in a collapsed `.Step` block, and add a collapsed `.Done` block that says what was actually done, where it differs from the plan, and ends with the commit's subject and short hash.
- Documentation is AsciiDoc, one sentence per line (the `kodein-asciidoc-writing` skill has the rules). This file is Markdown because the name is a convention.
- Ask before taking an architecture or technology decision that the plan does not settle, and say what the options cost. Report what could not be verified (a real keyboard, another platform) instead of implying it was.
