package machine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Checks a game submission against finite examples using isolated, bounded machines. */
public final class GameEngine {
    private static final int MAX_CASES = 512;
    private static final int MAX_STEPS = 2_000_000;
    private static final int MAX_TOTAL_STEPS = 20_000_000;

    private GameEngine() {}

    /** No source file or toolbox library is read from disk, and compilation prints no listing. */
    public static GameResult check(GameLevel level, String source, Set<GameFeature> available,
            Map<String, String> libraries, BooleanSupplier cancelled, IntConsumer progress) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(libraries, "libraries");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(progress, "progress");
        List<GameResult.CaseResult> results = new ArrayList<>();
        try {
            cancellation(cancelled);
            if (!available.containsAll(level.requiredFeatures())) {
                return failure("This level requires tools that are still locked.", results, null);
            }
            validateLimits(level);
            validateWorld(level, available);
            GameSyntax.Analysis syntax = GameSyntax.inspect(source, available, libraries, cancelled,
                    level.id(), GameBookLevels.requiredMacro(level.id()), GameLanguage.versionFor(level, available),
                    !GameWorld.usesDiagrams(level));
            for (GameFeature required : level.requiredFeatures()) {
                if (!syntax.usedFeatures().contains(required)) {
                    return failure("This level asks you to use " + required.title() + " in your solution.", results, null);
                }
            }
            int totalSteps = 0;
            for (GameLevel.TestCase test : level.cases()) {
                cancellation(cancelled);
                CompiledCase compiled = compileCase(syntax, test, cancelled);
                int budget = Math.min(level.maxSteps(), MAX_TOTAL_STEPS - totalSteps);
                GameDebugSession execution = new GameDebugSession(level, test, -1, compiled, budget);
                while (!execution.isStopped()) {
                    cancellation(cancelled);
                    execution.step();
                }
                totalSteps += execution.getStepCount();
                Map<String, Integer> actual = execution.getOutputValues();
                boolean halted = execution.getStatus() == GameDebugSession.Status.HALTED;
                boolean passed = halted && test.expected().equals(actual);
                String message = halted ? (passed ? "Passed." : "The program halted with different output values.")
                        : execution.getMessage();
                results.add(new GameResult.CaseResult(test.inputs(), test.expected(), actual,
                    execution.getStepCount(), passed, message));
                progress.accept(results.size());
                if (!passed) {
                    SourceTrace trace = execution.getCurrentTrace();
                    SourceLocation location = originalLocation(trace == null ? null : trace.location(), compiled.prepared, syntax.source());
                    return failure("Case " + results.size() + ": " + message + " Inputs " + test.inputs()
                        + "; expected " + test.expected() + "; actual " + actual + ".", results, location);
                }
            }
            return new GameResult(true, "Passed all " + results.size() + " test cases.", results, null);
        } catch (CancellationException exception) {
            return failure("Check cancelled.", results, null);
        } catch (GameSyntax.Failure exception) {
            return failure(exception.getMessage(), results, exception.location);
        } catch (IOException | ParseException | RuntimeException | TokenMgrError exception) {
            String message = exception.getMessage();
            return failure("Could not check this program: " + (message == null ? exception.getClass().getSimpleName() : message), results, null);
        }
    }

    private static GameResult failure(String message, List<GameResult.CaseResult> cases, SourceLocation location) {
        return new GameResult(false, message, cases, location);
    }

    static void validateLimits(GameLevel level) throws GameSyntax.Failure {
        if (level.cases().size() > MAX_CASES || level.maxSteps() > MAX_STEPS) {
            throw new GameSyntax.Failure("This level exceeds the game's checking resource limits.", null);
        }
    }

    static void validateWorld(GameLevel level, Set<GameFeature> available) throws GameSyntax.Failure {
        if (GameWorld.usesDiagrams(level) && !GameWorld.hasStructuredControls(available)) {
            throw new GameSyntax.Failure("Unlock do/while, if/else, and while before entering this world.", null);
        }
    }

    private record Change(int start, int end, String replacement) {}
    record Prepared(String text, List<Change> changes) {}
    record CompiledCase(CompilationResult program, Prepared prepared, Map<String, Integer> outputs,
            Map<String, Integer> cells, LanguageVersion version) {}

    /** Shared input substitution, virtual imports, output binding, and compiled-image limits. */
    static CompiledCase compileCase(GameSyntax.Analysis syntax, GameLevel.TestCase test,
            BooleanSupplier cancelled) throws GameSyntax.Failure, IOException, ParseException {
        return compileCase(syntax, test, cancelled, true);
    }

    static CompiledCase compileCase(GameSyntax.Analysis syntax, GameLevel.TestCase test,
            BooleanSupplier cancelled, boolean requireOutputs) throws GameSyntax.Failure, IOException, ParseException {
        Prepared prepared = prepare(syntax, test);
        Map<String, Integer> outputs = outputAddresses(syntax, test.expected(), requireOutputs);
        cancellation(cancelled);
        CompilationResult compiled = Compiler.compileWithSourceMap(
                new SourceDocument(GameSyntax.MAIN, prepared.text), path -> {
                    String text = syntax.libraries().get(path.toString());
                    if (text == null) throw new IOException("Toolbox library is not available: " + path);
                    return new SourceDocument(Path.of("toolbox", path.toString()), text);
                }, null, syntax.version());
        cancellation(cancelled);
        if (compiled.bytecode().size() > GameSyntax.MAX_CODE || compiled.codeStart() > 16_384) {
            throw new GameSyntax.Failure("Compiled program exceeds the game's memory limit.", null);
        }
        for (int address : outputs.values()) {
            if (address < 1 || address >= compiled.codeStart()) {
                throw new GameSyntax.Failure("An output port lies outside the compiled data segment.", null);
            }
        }
        Map<String, Integer> cells = new LinkedHashMap<>();
        syntax.globals().values().stream().sorted(Comparator.comparingInt(GameSyntax.Declaration::address))
                .forEach(declaration -> {
                    for (int index = 0; index < declaration.length(); index++) {
                        String name = declaration.array() ? declaration.name() + "[" + index + "]" : declaration.name();
                        cells.put(name, declaration.address() + index);
                    }
                });
        cells.putAll(outputs);
        return new CompiledCase(compiled, prepared, java.util.Collections.unmodifiableMap(outputs),
                java.util.Collections.unmodifiableMap(cells), syntax.version());
    }

    private static Prepared prepare(GameSyntax.Analysis syntax, GameLevel.TestCase test) throws GameSyntax.Failure {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Integer> input : test.inputs().entrySet()) {
            GameSyntax.Declaration declaration = syntax.globals().get(input.getKey());
            if (declaration == null) throw new GameSyntax.Failure("Declare input " + input.getKey() + " as a scalar global with a numeric initializer.", null);
            if (declaration.array() || !declaration.initializer().literal()) {
                throw new GameSyntax.Failure("Input " + input.getKey() + " must be a scalar global with a numeric initializer.", declaration.token());
            }
            if (input.getValue() == null || input.getValue() < 0) {
                throw new GameSyntax.Failure("Test inputs must be nonnegative integers.", declaration.token());
            }
            GameSyntax.Lexeme literal = declaration.initializer().token();
            changes.add(new Change(literal.start(), literal.end(), input.getValue().toString()));
        }
        changes.sort(Comparator.comparingInt(Change::start));
        StringBuilder edited = new StringBuilder();
        int previous = 0;
        for (Change change : changes) {
            edited.append(syntax.source(), previous, change.start).append(change.replacement);
            previous = change.end;
        }
        edited.append(syntax.source(), previous, syntax.source().length());
        return new Prepared(edited.toString(), List.copyOf(changes));
    }

    private static Map<String, Integer> outputAddresses(GameSyntax.Analysis syntax, Map<String, Integer> expected, boolean required)
            throws GameSyntax.Failure {
        Map<String, Integer> addresses = new LinkedHashMap<>();
        for (String port : expected.keySet()) {
            String name = port;
            Integer index = null;
            int bracket = port.indexOf('[');
            if (bracket >= 0) {
                name = port.substring(0, bracket);
                try {
                    if (!port.endsWith("]")) throw new NumberFormatException();
                    String digits = port.substring(bracket + 1, port.length() - 1);
                    if (digits.isEmpty() || !digits.chars().allMatch(character -> character >= '0' && character <= '9')) throw new NumberFormatException();
                    index = Integer.parseInt(digits);
                } catch (NumberFormatException exception) {
                    throw new GameSyntax.Failure("Invalid output port " + port + ".", null);
                }
            }
            GameSyntax.Declaration declaration = syntax.globals().get(name);
            if (declaration == null) {
                if (!required) continue;
                throw new GameSyntax.Failure("Declare global output " + name + ".", null);
            }
            if (index != null && (!declaration.array() || index >= declaration.length())) {
                if (!required) continue;
                throw new GameSyntax.Failure("Output port " + port + " is outside the declared array.", declaration.token());
            }
            addresses.put(port, declaration.address() + (index == null ? 0 : index));
        }
        return addresses;
    }

    static void cancellation(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static SourceLocation originalLocation(SourceLocation location, Prepared prepared, String original) {
        if (location == null || !GameSyntax.MAIN.equals(location.file())) return location;
        int start = originalOffset(location.startOffset(), prepared.changes);
        int end = originalOffset(location.endOffset(), prepared.changes);
        if (start < 0 || end < start || end > original.length()) return null;
        int line = 1, column = 1;
        for (int position = 0; position < start; ++position) {
            if (original.charAt(position) == '\n') { ++line; column = 1; } else { ++column; }
        }
        return new SourceLocation(location.file(), start, end, line, column);
    }

    private static int originalOffset(int editedOffset, List<Change> changes) {
        int delta = 0;
        for (Change change : changes) {
            int editedStart = change.start + delta;
            if (editedOffset <= editedStart) break;
            int editedEnd = editedStart + change.replacement.length();
            if (editedOffset < editedEnd) return change.start + Math.min(editedOffset - editedStart, change.end - change.start);
            delta += change.replacement.length() - (change.end - change.start);
        }
        return editedOffset - delta;
    }
}
