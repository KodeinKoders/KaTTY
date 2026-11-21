package app.katty.terminal;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A program that the tests run in a terminal where there is no `/bin/sh` (Windows): it runs the part of `sh` that their scripts use, the same way
 * (`printf`, `echo`, `sleep`, `stty raw -echo`, `head`, `od`, `tr -d`, `seq`, `yes`, `cat`, `pwd`, pipes, `;` and `>/dev/null`), and refuses
 * anything else, so that a script it does not know fails instead of doing something else. `TestProgramTest` compares it with `/bin/sh`.
 *
 * Java with no dependency: it is started with the folder of its class as its only classpath. The script comes in Base64, so that no quoting of the
 * command line changes it.
 */
public final class TestProgram {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private TestProgram() {}

    public static void main(String[] args) throws Throwable {
        String script = new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8);
        if (WINDOWS) {
            try {
                Console.utf8();
            } catch (IllegalStateException e) {
                // No console: started with pipes (TestProgramTest), not in a terminal. There is nothing to set.
            }
        }
        OutputStream out = new FileOutputStream(FileDescriptor.out);
        InputStream in = new FileInputStream(FileDescriptor.in);
        try {
            run(script, in, out);
        } catch (Unsupported e) {
            out.write(("TestProgram: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            System.exit(127);
        }
        out.flush();
        System.exit(0);
    }

    /** What the program does not know: a script that needs it fails, loudly. */
    static final class Unsupported extends RuntimeException {
        Unsupported(String what) {
            super("not supported: " + what);
        }
    }

    /** Runs [script]: its commands one after the other, each a pipeline. */
    static void run(String script, InputStream in, OutputStream out) throws Throwable {
        for (List<List<String>> pipeline : parse(script)) runPipeline(pipeline, in, out);
    }

    // The script as commands separated by `;`, each a pipeline of commands separated by `|`, each a list of words.
    static List<List<List<String>>> parse(String script) {
        List<List<List<String>>> commands = new ArrayList<>();
        List<List<String>> pipeline = new ArrayList<>();
        List<String> words = new ArrayList<>();
        StringBuilder word = null;
        int i = 0;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == '\'') {
                int end = script.indexOf('\'', i + 1);
                if (end < 0) throw new Unsupported("an unclosed quote");
                if (word == null) word = new StringBuilder();
                word.append(script, i + 1, end);
                i = end + 1;
            } else if (c == '"') {
                if (word == null) word = new StringBuilder();
                i++;
                while (i < script.length() && script.charAt(i) != '"') {
                    char d = script.charAt(i);
                    if (d == '\\' && i + 1 < script.length() && "\"\\$`".indexOf(script.charAt(i + 1)) >= 0) {
                        word.append(script.charAt(i + 1));
                        i += 2;
                    } else if (d == '$') {
                        i = expand(script, i, word);
                    } else if (d == '`') {
                        throw new Unsupported("command substitution");
                    } else {
                        word.append(d);
                        i++;
                    }
                }
                if (i >= script.length()) throw new Unsupported("an unclosed quote");
                i++;
            } else if (c == '\\') {
                if (i + 1 >= script.length()) throw new Unsupported("a backslash at the end");
                if (word == null) word = new StringBuilder();
                word.append(script.charAt(i + 1));
                i += 2;
            } else if (c == '$') {
                if (word == null) word = new StringBuilder();
                i = expand(script, i, word);
            } else if (c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '|') {
                if (word != null) {
                    words.add(word.toString());
                    word = null;
                }
                if (c == '|' || c == ';' || c == '\n') {
                    if (c == '|' && i + 1 < script.length() && script.charAt(i + 1) == '|') throw new Unsupported("||");
                    if (!words.isEmpty()) pipeline.add(words);
                    words = new ArrayList<>();
                    if (c != '|') {
                        if (!pipeline.isEmpty()) commands.add(pipeline);
                        pipeline = new ArrayList<>();
                    }
                }
                i++;
            } else if ("&<(){}`".indexOf(c) >= 0) {
                throw new Unsupported("'" + c + "'");
            } else {
                if (word == null) word = new StringBuilder();
                word.append(c);
                i++;
            }
        }
        if (word != null) words.add(word.toString());
        if (!words.isEmpty()) pipeline.add(words);
        if (!pipeline.isEmpty()) commands.add(pipeline);
        return commands;
    }

    // `$NAME`, from the environment, which is all that the scripts expand. Returns where the name ends.
    private static int expand(String script, int dollar, StringBuilder word) {
        int end = dollar + 1;
        while (end < script.length() && (Character.isLetterOrDigit(script.charAt(end)) || script.charAt(end) == '_')) end++;
        if (end == dollar + 1) throw new Unsupported("'$' that is not a variable");
        String value = System.getenv(script.substring(dollar + 1, end));
        if (value != null) word.append(value);
        return end;
    }

    private static void runPipeline(List<List<String>> pipeline, InputStream in, OutputStream out) throws Throwable {
        if (pipeline.size() == 1) {
            runCommand(pipeline.get(0), in, out);
            return;
        }
        // Each command of a pipeline but the last writes to the next one, on a thread of its own, as a shell runs them at once (`yes | head`).
        List<Thread> threads = new ArrayList<>();
        InputStream next = in;
        for (int index = 0; index < pipeline.size() - 1; index++) {
            List<String> command = pipeline.get(index);
            PipedInputStream reader = new PipedInputStream(65536);
            PipedOutputStream writer = new PipedOutputStream(reader);
            InputStream source = next;
            Thread thread = new Thread(() -> {
                try (writer) {
                    runCommand(command, source, writer);
                } catch (IOException e) {
                    // The next command stopped reading (`head`): this one stops too, as with SIGPIPE.
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
            next = reader;
        }
        runCommand(pipeline.get(pipeline.size() - 1), next, out);
        next.close();
    }

    private static void runCommand(List<String> words, InputStream in, OutputStream out) throws Throwable {
        List<String> arguments = new ArrayList<>(words);
        // `>/dev/null`, written in one word or two: what the command writes goes nowhere.
        OutputStream target = out;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equals(">/dev/null")) {
                arguments.remove(index--);
                target = OutputStream.nullOutputStream();
            } else if (argument.equals(">") && index + 1 < arguments.size() && arguments.get(index + 1).equals("/dev/null")) {
                arguments.remove(index + 1);
                arguments.remove(index--);
                target = OutputStream.nullOutputStream();
            } else if (argument.startsWith(">") || argument.startsWith("2>")) {
                throw new Unsupported("the redirection " + argument);
            }
        }
        String name = arguments.get(0);
        List<String> rest = arguments.subList(1, arguments.size());
        switch (name) {
            case "printf" -> target.write(printf(rest));
            // As the echo of macOS's sh: `-n` is no option, and is written.
            case "echo" -> target.write((String.join(" ", rest) + "\n").getBytes(StandardCharsets.UTF_8));
            case "sleep" -> {
                out.flush();
                Thread.sleep((long) (Double.parseDouble(single(name, rest)) * 1000));
            }
            case "stty" -> stty(rest, out);
            case "head" -> head(rest, in, target);
            case "od" -> od(rest, in, target);
            case "tr" -> tr(rest, in, target);
            case "seq" -> {
                long first = rest.size() == 2 ? Long.parseLong(rest.get(0)) : 1;
                long last = Long.parseLong(rest.get(rest.size() - 1));
                StringBuilder text = new StringBuilder();
                for (long n = first; n <= last; n++) {
                    text.append(n).append('\n');
                    if (text.length() > 65536) {
                        target.write(text.toString().getBytes(StandardCharsets.UTF_8));
                        text.setLength(0);
                    }
                }
                target.write(text.toString().getBytes(StandardCharsets.UTF_8));
            }
            case "yes" -> {
                byte[] line = ((rest.isEmpty() ? "y" : String.join(" ", rest)) + "\n").getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream block = new ByteArrayOutputStream();
                while (block.size() < 65536) block.write(line);
                byte[] bytes = block.toByteArray();
                while (true) target.write(bytes);
            }
            case "cat" -> {
                if (rest.isEmpty()) in.transferTo(target);
                else for (String file : rest) target.write(Files.readAllBytes(Path.of(file)));
            }
            case "pwd" -> target.write((Path.of("").toAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));
            case "true" -> {}
            case "exit" -> {
                out.flush();
                System.exit(rest.isEmpty() ? 0 : Integer.parseInt(single(name, rest)));
            }
            default -> throw new Unsupported("the command " + name);
        }
        target.flush();
        out.flush();
    }

    private static String single(String command, List<String> arguments) {
        if (arguments.size() != 1) throw new Unsupported(command + " " + arguments);
        return arguments.get(0);
    }

    // printf's format, with its escapes and %%, %s, %d and %c, used again while arguments are left.
    static byte[] printf(List<String> arguments) {
        if (arguments.isEmpty()) throw new Unsupported("printf without a format");
        String format = arguments.get(0);
        List<String> values = arguments.subList(1, arguments.size());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int used = 0;
        do {
            int consumed = 0;
            for (int i = 0; i < format.length(); i++) {
                char c = format.charAt(i);
                if (c == '\\') {
                    i = escape(format, i, bytes);
                } else if (c == '%') {
                    if (i + 1 >= format.length()) throw new Unsupported("a '%' at the end of a format");
                    char conversion = format.charAt(++i);
                    if (conversion == '%') {
                        bytes.write('%');
                        continue;
                    }
                    String value = used + consumed < values.size() ? values.get(used + consumed) : "";
                    consumed++;
                    switch (conversion) {
                        case 's' -> bytes.writeBytes(value.getBytes(StandardCharsets.UTF_8));
                        case 'd' -> bytes.writeBytes(String.valueOf(value.isEmpty() ? 0 : Long.parseLong(value)).getBytes(StandardCharsets.UTF_8));
                        case 'c' -> { if (!value.isEmpty()) bytes.writeBytes(value.substring(0, 1).getBytes(StandardCharsets.UTF_8)); }
                        default -> throw new Unsupported("the conversion %" + conversion);
                    }
                } else {
                    // A whole code point, with its low surrogate when it has one.
                    int end = Character.isHighSurrogate(c) && i + 1 < format.length() ? i + 2 : i + 1;
                    bytes.writeBytes(format.substring(i, end).getBytes(StandardCharsets.UTF_8));
                    i = end - 1;
                }
            }
            used += consumed;
            if (consumed == 0) break;
        } while (used < values.size());
        return bytes.toByteArray();
    }

    // A backslash escape of printf at [backslash], written to [bytes]: returns the index of its last character.
    private static int escape(String text, int backslash, ByteArrayOutputStream bytes) {
        if (backslash + 1 >= text.length()) {
            bytes.write('\\');
            return backslash;
        }
        char c = text.charAt(backslash + 1);
        switch (c) {
            case 'a' -> bytes.write(7);
            case 'b' -> bytes.write(8);
            case 'e' -> bytes.write(27);
            case 'f' -> bytes.write(12);
            case 'n' -> bytes.write('\n');
            case 'r' -> bytes.write('\r');
            case 't' -> bytes.write('\t');
            case 'v' -> bytes.write(11);
            case '\\' -> bytes.write('\\');
            case '\'' -> bytes.write('\'');
            case '"' -> bytes.write('"');
            case 'x' -> {
                int end = backslash + 2;
                while (end < text.length() && end < backslash + 4 && Character.digit(text.charAt(end), 16) >= 0) end++;
                bytes.write(Integer.parseInt(text.substring(backslash + 2, end), 16));
                return end - 1;
            }
            default -> {
                if (c >= '0' && c <= '7') {
                    // Up to three octal digits, a leading 0 included, as sh's printf reads its format (\0033 is \003 and a 3).
                    int start = backslash + 1;
                    int end = start;
                    while (end < text.length() && end < start + 3 && text.charAt(end) >= '0' && text.charAt(end) <= '7') end++;
                    bytes.write(Integer.parseInt(text.substring(start, end), 8));
                    return end - 1;
                }
                bytes.write('\\');
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return backslash + 1;
    }

    private static void stty(List<String> arguments, OutputStream out) throws Throwable {
        out.flush();
        if (!WINDOWS) {
            // The real one, on the terminal that this program has.
            new ProcessBuilder(prepend("stty", arguments)).inheritIO().start().waitFor();
            return;
        }
        if (arguments.equals(List.of("raw", "-echo"))) Console.raw();
        else if (arguments.equals(List.of("size"))) out.write((Console.size() + "\n").getBytes(StandardCharsets.UTF_8));
        else throw new Unsupported("stty " + arguments);
    }

    private static List<String> prepend(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private static void head(List<String> arguments, InputStream in, OutputStream out) throws IOException {
        // What comes from another command of a pipeline is read in blocks; what comes from the terminal a byte at a time, so that nothing that
        // the next command reads is taken.
        if (in instanceof PipedInputStream && arguments.size() == 2 && arguments.get(0).equals("-n")) {
            long lines = Long.parseLong(arguments.get(1));
            byte[] buffer = new byte[65536];
            long seen = 0;
            int read;
            while (seen < lines && (read = in.read(buffer)) > 0) {
                int end = 0;
                while (end < read && seen < lines) if (buffer[end++] == '\n') seen++;
                out.write(buffer, 0, end);
            }
            out.flush();
            return;
        }
        if (arguments.size() == 1 && arguments.get(0).startsWith("-c")) {
            long count = Long.parseLong(arguments.get(0).substring(2));
            for (long n = 0; n < count; n++) {
                int b = in.read();
                if (b < 0) break;
                out.write(b);
            }
        } else if (arguments.size() == 2 && arguments.get(0).equals("-n")) {
            long lines = Long.parseLong(arguments.get(1));
            long seen = 0;
            while (seen < lines) {
                int b = in.read();
                if (b < 0) break;
                out.write(b);
                if (b == '\n') seen++;
            }
        } else {
            throw new Unsupported("head " + arguments);
        }
        out.flush();
    }

    // `od -An -c` and `od -An -tx1`: the bytes, sixteen to a line, as characters (and escapes and octal) or as hexadecimal.
    private static void od(List<String> arguments, InputStream in, OutputStream out) throws IOException {
        boolean characters;
        if (arguments.equals(List.of("-An", "-c"))) characters = true;
        else if (arguments.equals(List.of("-An", "-tx1"))) characters = false;
        else throw new Unsupported("od " + arguments);
        byte[] all = in.readAllBytes();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < all.length; i++) {
            int b = all[i] & 0xFF;
            String field;
            if (!characters) field = String.format("%02x", b);
            else field = switch (b) {
                case 0 -> "\\0";
                case 7 -> "\\a";
                case 8 -> "\\b";
                case 9 -> "\\t";
                case 10 -> "\\n";
                case 11 -> "\\v";
                case 12 -> "\\f";
                case 13 -> "\\r";
                default -> b >= 0x20 && b < 0x7F ? String.valueOf((char) b) : String.format("%03o", b);
            };
            text.append(characters ? String.format("%4s", field) : " " + field);
            if (i % 16 == 15 || i == all.length - 1) text.append('\n');
        }
        out.write(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    // `tr -d SET`: the bytes that are not in the set (whose escapes are printf's).
    private static void tr(List<String> arguments, InputStream in, OutputStream out) throws IOException {
        if (arguments.size() != 2 || !arguments.get(0).equals("-d")) throw new Unsupported("tr " + arguments);
        byte[] set = printfEscapes(arguments.get(1));
        int b;
        outer:
        while ((b = in.read()) >= 0) {
            for (byte removed : set) if ((removed & 0xFF) == b) continue outer;
            out.write(b);
        }
    }

    private static byte[] printfEscapes(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\\') i = escape(text, i, bytes);
            else bytes.writeBytes(String.valueOf(text.charAt(i)).getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /** The console of Windows, which ConPTY gives the program, through FFM on kernel32. */
    static final class Console {
        private static final int STD_INPUT_HANDLE = -10;
        private static final int STD_OUTPUT_HANDLE = -11;
        private static final int ENABLE_PROCESSED_INPUT = 0x1;
        private static final int ENABLE_LINE_INPUT = 0x2;
        private static final int ENABLE_ECHO_INPUT = 0x4;
        private static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x200;
        private static final int ENABLE_PROCESSED_OUTPUT = 0x1;
        private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x4;
        private static final int DISABLE_NEWLINE_AUTO_RETURN = 0x8;
        private static final int UTF8 = 65001;

        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

        private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(KERNEL32.find(name).orElseThrow(), descriptor);
        }

        private static final MethodHandle GET_STD_HANDLE = handle("GetStdHandle", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private static final MethodHandle GET_CONSOLE_MODE =
            handle("GetConsoleMode", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle SET_CONSOLE_MODE =
            handle("SetConsoleMode", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private static final MethodHandle GET_CONSOLE_SCREEN_BUFFER_INFO =
            handle("GetConsoleScreenBufferInfo", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle SET_CONSOLE_CP = handle("SetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        private static final MethodHandle SET_CONSOLE_OUTPUT_CP =
            handle("SetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        /** What is written is UTF-8, and its escape sequences go to the terminal, as on a Unix pty. */
        static void utf8() throws Throwable {
            int unused = (int) SET_CONSOLE_CP.invokeExact(UTF8);
            unused = (int) SET_CONSOLE_OUTPUT_CP.invokeExact(UTF8);
            update(STD_OUTPUT_HANDLE, ENABLE_PROCESSED_OUTPUT | ENABLE_VIRTUAL_TERMINAL_PROCESSING, 0);
        }

        /**
         * What `stty raw -echo` does: the keys come as they are typed, as the terminal's escape sequences, with no echo and no line editing, and
         * a newline that is written does not return the carriage.
         */
        static void raw() throws Throwable {
            update(STD_INPUT_HANDLE, ENABLE_VIRTUAL_TERMINAL_INPUT, ENABLE_PROCESSED_INPUT | ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT);
            update(STD_OUTPUT_HANDLE, DISABLE_NEWLINE_AUTO_RETURN, 0);
        }

        /** What `stty size` says: the rows and the columns of the window of the console, which ConPTY sizes as the terminal. */
        static String size() throws Throwable {
            MemorySegment handle = (MemorySegment) GET_STD_HANDLE.invokeExact(STD_OUTPUT_HANDLE);
            try (Arena arena = Arena.ofConfined()) {
                // CONSOLE_SCREEN_BUFFER_INFO: the size and the cursor (two COORDs), the attributes (a WORD), then the window (a SMALL_RECT:
                // left, top, right, bottom), at 10.
                MemorySegment info = arena.allocate(22, 2);
                if ((int) GET_CONSOLE_SCREEN_BUFFER_INFO.invokeExact(handle, info) == 0) throw new IllegalStateException("GetConsoleScreenBufferInfo failed");
                int left = info.get(ValueLayout.JAVA_SHORT, 10), top = info.get(ValueLayout.JAVA_SHORT, 12);
                int right = info.get(ValueLayout.JAVA_SHORT, 14), bottom = info.get(ValueLayout.JAVA_SHORT, 16);
                return (bottom - top + 1) + " " + (right - left + 1);
            }
        }

        private static void update(int which, int set, int cleared) throws Throwable {
            MemorySegment handle = (MemorySegment) GET_STD_HANDLE.invokeExact(which);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment mode = arena.allocate(ValueLayout.JAVA_INT);
                if ((int) GET_CONSOLE_MODE.invokeExact(handle, mode) == 0) throw new IllegalStateException("GetConsoleMode failed");
                int updated = (mode.get(ValueLayout.JAVA_INT, 0) | set) & ~cleared;
                if ((int) SET_CONSOLE_MODE.invokeExact(handle, updated) == 0) throw new IllegalStateException("SetConsoleMode failed");
            }
        }
    }
}
