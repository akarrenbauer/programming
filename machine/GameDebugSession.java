package machine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** One isolated, bounded execution of a frozen game submission and selected test case. */
public final class GameDebugSession {
    public enum Status { READY, HALTED, ERROR, LIMIT_REACHED }

    public static final class PreparationException extends Exception {
        private final SourceLocation location;

        private PreparationException(String message, SourceLocation location, Throwable cause) {
            super(message, cause);
            this.location = location;
        }

        public SourceLocation getLocation() { return location; }
    }

    private final GameLevel level;
    private final GameLevel.TestCase testCase;
    private final int caseIndex;
    private final CompilationResult program;
    private final LanguageVersion languageVersion;
    private final Map<String, Integer> outputs;
    private final Map<String, Integer> cells;
    private final Map<Integer, SourceTrace> declarations;
    private final int maxSteps;
    private final Machine machine = new Machine();
    private Status status;
    private String message;
    private SourceTrace previousTrace;

    /** Compile on a worker thread, then hand the session to one UI thread. No files are read or saved. */
    public static GameDebugSession prepare(GameLevel level, String source, Set<GameFeature> available,
            Map<String, String> libraries, int caseIndex, BooleanSupplier cancelled) throws PreparationException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(libraries, "libraries");
        Objects.requireNonNull(cancelled, "cancelled");
        if (caseIndex < 0 || caseIndex >= level.cases().size()) throw new IllegalArgumentException("Unknown test case " + caseIndex);
        try {
            GameEngine.cancellation(cancelled);
            GameEngine.validateLimits(level);
            GameEngine.validateWorld(level, available);
            LanguageVersion version = GameLanguage.editingVersionFor(level, available, source);
            return prepareVersion(level, source, available, libraries, caseIndex, cancelled, version);
        } catch (GameSyntax.Failure failure) {
            throw new PreparationException(failure.getMessage(), failure.location, failure);
        } catch (CancellationException cancelledCheck) {
            throw cancelledCheck;
        } catch (IOException | ParseException | RuntimeException | TokenMgrError failure) {
            throw new PreparationException("Could not prepare debugging: " + failure.getMessage(), null, failure);
        }
    }

    private static GameDebugSession prepareVersion(GameLevel level, String source, Set<GameFeature> available,
            Map<String, String> libraries, int caseIndex, BooleanSupplier cancelled, LanguageVersion version)
            throws GameSyntax.Failure, IOException, ParseException {
        // Debugging incomplete solutions does not require the exercise shape or requested macro definition.
        GameSyntax.Analysis syntax = GameSyntax.inspect(source, available, libraries, cancelled, null, null, version,
                !GameWorld.usesDiagrams(level));
        GameLevel.TestCase test = level.cases().get(caseIndex);
        return new GameDebugSession(level, test, caseIndex, GameEngine.compileCase(syntax, test, cancelled, false), level.maxSteps());
    }

    GameDebugSession(GameLevel level, GameLevel.TestCase testCase, int caseIndex,
            GameEngine.CompiledCase compiled, int maxSteps) {
        this.level = level;
        this.testCase = testCase;
        this.caseIndex = caseIndex;
        this.program = compiled.program();
        this.languageVersion = compiled.version();
        this.outputs = compiled.outputs();
        Map<String, Integer> named = new LinkedHashMap<>(compiled.cells());
        Set<Integer> namedAddresses = new HashSet<>(named.values());
        Map<Integer, SourceTrace> sources = new LinkedHashMap<>();
        for (CompilationResult.Variable variable : program.variables()) {
            boolean array = variable.length() > 1 || isArray(variable);
            for (int index = 0; index < variable.length(); index++) {
                int address = variable.address() + index;
                sources.put(address, variable.source());
                if (namedAddresses.add(address)) {
                    String name = variable.name() + (array ? "[" + index + "]" : "");
                    // Macro expansion allocates separate cells; a local may also shadow a global test port.
                    named.put(name + " @" + address, address);
                }
            }
        }
        this.cells = Collections.unmodifiableMap(named);
        this.declarations = Collections.unmodifiableMap(sources);
        this.maxSteps = Math.max(0, maxSteps);
        restart();
    }

    public GameLevel getLevel() { return level; }
    public GameLevel.TestCase getTestCase() { return testCase; }
    public int getCaseIndex() { return caseIndex; }
    public CompilationResult getProgram() { return program; }
    public LanguageVersion getLanguageVersion() { return languageVersion; }
    public Map<String, Integer> getNamedAddresses() { return cells; }
    /** Source declaration and caller chain for a named cell; undefined test ports have no declaration. */
    public SourceTrace getVariableSource(String name) { return declarations.get(cells.get(name)); }
    public Status getStatus() { return status; }
    public String getMessage() { return message; }
    public int getStepCount() { return machine.getStepCounter(); }
    public int getMaxSteps() { return maxSteps; }
    public int getInstructionPointer() { return machine.getInstructionPointer(); }
    public int getMemorySize() { return machine.getMemorySize(); }
    public boolean isStopped() { return status != Status.READY; }

    private boolean isArray(CompilationResult.Variable variable) {
        SourceLocation location = variable.source().location();
        String source = program.sources().get(location.file());
        if (source == null) return false;
        String declaration = source.substring(location.startOffset(), location.endOffset()).replaceAll("(?m)//[^\\r\\n]*", " ");
        return Pattern.compile("\\bvar\\s+" + Pattern.quote(variable.name()) + "\\s*\\[").matcher(declaration).find();
    }

    public int getMemoryValue(int address) {
        if (address < 0 || address >= getMemorySize()) throw new IndexOutOfBoundsException(address);
        return machine.get(address);
    }

    public List<Integer> getMemory() {
        List<Integer> snapshot = new ArrayList<>(getMemorySize());
        for (int address = 0; address < getMemorySize(); address++) snapshot.add(machine.get(address));
        return List.copyOf(snapshot);
    }

    public Map<String, Integer> getOutputValues() {
        Map<String, Integer> values = new LinkedHashMap<>();
        outputs.forEach((name, address) -> values.put(name, machine.get(address)));
        return Collections.unmodifiableMap(values);
    }

    /** Provenance points into getProgram().sources(), whose input literals are the selected case's actual values. */
    public SourceTrace getCurrentTrace() {
        return program.sourceMap().getOrDefault(getInstructionPointer(), previousTrace);
    }

    public void restart() {
        machine.load(program.bytecode());
        previousTrace = null;
        status = Status.READY;
        message = "Ready to step.";
        inspectPosition();
    }

    /** Execute at most one instruction. Guards run before any unsafe memory mutation. HALT is never counted. */
    public boolean step() {
        if (isStopped()) return false;
        int pointer = getInstructionPointer();
        int opcode = machine.get(pointer);
        int next;
        if (opcode == 1) {
            next = pointer + 1;
        } else if (opcode == 2 || opcode == 3) {
            int target = machine.get(pointer + 1);
            if (target < 1 || target >= program.codeStart()) {
                stop(Status.ERROR, "An instruction would write outside the data cells (or change code/the instruction pointer).");
                return false;
            }
            if (machine.get(target) < 0 || opcode == 2 && machine.get(target) == Integer.MAX_VALUE) {
                stop(Status.ERROR, "Arithmetic would overflow the nonnegative integer range.");
                return false;
            }
            next = pointer + 2;
        } else if (opcode == 4) {
            int test = machine.get(pointer + 1);
            if (test < 0 || test >= program.codeStart()) {
                stop(Status.ERROR, "A jump would read outside the data cells.");
                return false;
            }
            next = machine.get(test) == 0 ? pointer + 3 : machine.get(pointer + 2);
        } else {
            stop(Status.ERROR, "Execution encountered an invalid instruction.");
            return false;
        }
        if (!program.sourceMap().containsKey(next)) {
            stop(Status.ERROR, "Execution would jump or fall outside the compiled instructions; end with HALT.");
            return false;
        }
        previousTrace = getCurrentTrace();
        machine.step();
        inspectPosition();
        return true;
    }

    /** Bounded source stepping also handles long initializers and loops that stay on one source range. */
    public int stepStatement(int instructionLimit) {
        if (instructionLimit < 1) throw new IllegalArgumentException("A positive instruction limit is required");
        SourceTrace start = getCurrentTrace();
        int before = getStepCount();
        do {
            if (!step()) break;
        } while (!isStopped() && getStepCount() - before < instructionLimit && Objects.equals(start, getCurrentTrace()));
        return getStepCount() - before;
    }

    private void inspectPosition() {
        int pointer = getInstructionPointer();
        if (pointer < program.codeStart() || pointer >= program.bytecode().size() || !program.sourceMap().containsKey(pointer)) {
            stop(Status.ERROR, "Execution left the compiled instruction addresses; end with HALT.");
        } else if (machine.get(pointer) == 0) {
            stop(Status.HALTED, "HALT reached.");
        } else if (getStepCount() >= maxSteps) {
            stop(Status.LIMIT_REACHED, "Execution reached the step limit.");
        } else {
            status = Status.READY;
            message = "Ready to step.";
        }
    }

    private void stop(Status status, String message) {
        this.status = status;
        this.message = message;
    }
}
