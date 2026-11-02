package machine;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Projects actual machine steps onto the unchanged source snapshots used for compilation. */
public final class FlowchartExecution {
    private static final Pattern DECLARATION = Pattern.compile("\\bvar\\s+[A-Za-z_]");
    public record Frame(int nextVertex, List<Flowchart.Edge> transitions, boolean initial) {
        public Frame { transitions = List.copyOf(transitions); }
    }

    private final CompilationResult program;
    private final Map<Path, String> sources;
    private final Map<Path, Projection> projections = new LinkedHashMap<>();
    private final Map<Path, List<CompilationResult.Variable>> variablesByFile = new LinkedHashMap<>();
    private int expectedPointer;
    private int expectedSteps;
    private boolean initial = true;

    public FlowchartExecution(CompilationResult program) {
        this(program, null);
    }

    /** The optional main snapshot also supports a compiled, unsaved editor without inventing a file path. */
    public FlowchartExecution(CompilationResult program, SourceDocument main) {
        this.program = Objects.requireNonNull(program, "program");
        Map<Path, String> snapshots = new LinkedHashMap<>(program.sources());
        if (main != null && main.file() == null) snapshots.put(null, main.text());
        sources = Collections.unmodifiableMap(snapshots);
        for (CompilationResult.Variable variable : program.variables()) {
            if (variable.length() <= 0) continue;
            SourceLocation location = variable.source().location();
            String original = sources.get(location.file());
            if (original != null) {
                String declaration = original.substring(location.startOffset(), Math.min(location.endOffset(), original.length()))
                        .replaceAll("(?m)//[^\\r\\n]*", " ");
                // Literal macro arguments create private storage at a callsite, not a source declaration.
                if (!DECLARATION.matcher(declaration).find()) continue;
            }
            variablesByFile.computeIfAbsent(location.file(), unused -> new ArrayList<>()).add(variable);
        }
        expectedPointer = program.bytecode().isEmpty() ? 0 : program.bytecode().get(0);
        for (var source : sources.entrySet()) {
            try {
                StructuredDiagram document;
                try {
                    document = StructuredDiagram.parseDocument(source.getValue(), LanguageVersion.STRUCTURED, true);
                } catch (StructuredDiagram.ParseException legacy) {
                    document = StructuredDiagram.parseDocument(source.getValue(), LanguageVersion.BEFORE_DO_WHILE, true);
                }
                if (document.hasMain()) projections.put(source.getKey(), new Projection(source.getKey(), Flowchart.from(document)));
            } catch (StructuredDiagram.ParseException | IllegalArgumentException unavailable) {
                // Compilation also supports documents larger than the interactive diagram limit.
            }
        }
    }

    /** Exact frozen compiler input, including the optional unnamed main snapshot at a null key. */
    public String source(Path file) { return sources.get(file); }

    public void step(Machine machine) { step(machine, 1); }

    /** Every primitive instruction is observed, including all steps in Run and Step n batches. */
    public void step(Machine machine, int count) {
        synchronize(machine);
        for (int index = 0; index < count && !machine.isHalted(); ++index) {
            int before = machine.getInstructionPointer();
            int opcode = machine.get(before);
            boolean taken = opcode == 4 && machine.get(machine.get(before + 1)) != 0;
            machine.step();
            int after = machine.getInstructionPointer();
            for (Projection projection : projections.values()) projection.advance(before, after, taken);
            expectedPointer = after;
            expectedSteps = machine.getStepCounter();
            initial = false;
        }
    }

    /** Manual instruction-pointer changes discard history; they never fabricate a begin transition. */
    public void resetHistory(Machine machine) {
        expectedPointer = machine.getInstructionPointer();
        expectedSteps = machine.getStepCounter();
        initial = false;
        projections.values().forEach(projection -> projection.transition = List.of());
    }

    public Frame frame(Flowchart graph, Path file, Machine machine) {
        synchronize(machine);
        Projection projection = projections.get(file);
        if (graph == null || projection == null || !graph.document().source().equals(sources.get(file))) {
            return new Frame(-1, List.of(), false);
        }
        int next = projection.vertexAt(machine.getInstructionPointer());
        List<Flowchart.Edge> transition = initial ? projection.path(0, next, null) : projection.transition;
        return new Frame(next, transition, initial);
    }

    /** Proven declaration cells, never source-reparsed guesses about allocation or macro-local names. */
    public Map<Integer, String> values(Path file, Machine machine) {
        synchronize(machine);
        SourceTrace active = program.sourceMap().get(machine.getInstructionPointer());
        Map<Integer, List<CompilationResult.Variable>> bySource = new LinkedHashMap<>();
        for (CompilationResult.Variable variable : variablesByFile.getOrDefault(file, List.of())) {
            if (!variable.source().callers().isEmpty()
                    && (active == null || !isPrefix(variable.source().callers(), active.callers()))) continue;
            bySource.computeIfAbsent(variable.source().location().startOffset(), unused -> new ArrayList<>()).add(variable);
        }
        Map<Integer, String> values = new LinkedHashMap<>();
        for (var entry : bySource.entrySet()) {
            // The same helper declaration can be expanded into several distinct storage locations.
            List<CompilationResult.Variable> candidates = entry.getValue();
            if (candidates.size() != 1) continue;
            CompilationResult.Variable variable = candidates.get(0);
            StringBuilder value = new StringBuilder();
            if (variable.length() == 1) value.append(machine.get(variable.address()));
            else {
                value.append('[');
                int displayed = Math.min(variable.length(), 32);
                for (int index = 0; index < displayed; ++index) {
                    if (index != 0) value.append(", ");
                    value.append(machine.get(variable.address() + index));
                }
                if (displayed < variable.length()) value.append(", …");
                value.append(']');
            }
            values.put(entry.getKey(), value.toString());
        }
        return Collections.unmodifiableMap(values);
    }

    private static boolean isPrefix(List<SourceLocation> prefix, List<SourceLocation> locations) {
        return prefix.size() <= locations.size() && locations.subList(0, prefix.size()).equals(prefix);
    }

    private void synchronize(Machine machine) {
        if (machine.getInstructionPointer() != expectedPointer || machine.getStepCounter() != expectedSteps) resetHistory(machine);
    }

    private final class Projection {
        final Path file;
        final Flowchart graph;
        final Map<Integer, Integer> verticesByPointer = new HashMap<>();
        final Map<SourceTrace, Integer> verticesByTrace = new HashMap<>();
        final Set<Integer> executable = new HashSet<>();
        final Map<Integer, List<Flowchart.Edge>> outgoing = new HashMap<>();
        List<Flowchart.Edge> transition = List.of();

        Projection(Path file, Flowchart graph) {
            this.file = file;
            this.graph = graph;
            for (Flowchart.Edge edge : graph.edges()) outgoing.computeIfAbsent(edge.from(), unused -> new ArrayList<>()).add(edge);
            for (int address : program.sourceMap().keySet()) {
                int vertex = mapPointer(address);
                verticesByPointer.put(address, vertex);
                if (vertex >= 0) executable.add(vertex);
            }
        }

        int vertexAt(int pointer) {
            Integer vertex = verticesByPointer.get(pointer);
            if (vertex != null) return vertex;
            // A source program may end without an explicit HALT; memory past the code is zero.
            if (pointer == program.bytecode().size()) {
                for (Flowchart.Vertex candidate : graph.vertices()) if (candidate.kind() == Flowchart.Kind.END) return candidate.id();
            }
            return -1;
        }

        int mapPointer(int pointer) {
            String role = program.controlRoles().get(pointer);
            if ("if-exit".equals(role) || "while-back".equals(role)) {
                int target = program.bytecode().get(pointer + 2);
                if (target != pointer) return mapPointerWithoutContinuation(target, new HashSet<>());
            }
            return mapTrace(program.sourceMap().get(pointer));
        }

        int mapPointerWithoutContinuation(int pointer, Set<Integer> visited) {
            if (!visited.add(pointer)) return mapTrace(program.sourceMap().get(pointer));
            String role = program.controlRoles().get(pointer);
            if ("if-exit".equals(role) || "while-back".equals(role)) {
                return mapPointerWithoutContinuation(program.bytecode().get(pointer + 2), visited);
            }
            if (pointer == program.bytecode().size()) {
                for (Flowchart.Vertex candidate : graph.vertices()) if (candidate.kind() == Flowchart.Kind.END) return candidate.id();
            }
            return mapTrace(program.sourceMap().get(pointer));
        }

        int mapTrace(SourceTrace trace) {
            if (trace == null) return -1;
            return verticesByTrace.computeIfAbsent(trace, this::resolveTrace);
        }

        int resolveTrace(SourceTrace trace) {
            // Main-program macro calls remain opaque process boxes while their expansion executes.
            for (SourceLocation caller : trace.callers()) {
                int vertex = mapLocation(caller);
                if (vertex >= 0) return vertex;
            }
            return mapLocation(trace.location());
        }

        int mapLocation(SourceLocation location) {
            if (!Objects.equals(location.file(), file)) return -1;
            int best = -1, smallest = Integer.MAX_VALUE;
            for (Flowchart.Vertex vertex : graph.vertices()) {
                StructuredDiagram.Node owner = vertex.owner();
                if (owner == null) continue;
                int offset = location.startOffset();
                if (offset < owner.start() || offset >= owner.end()) continue;
                if (owner.kind() == StructuredDiagram.Kind.DO_WHILE && vertex.kind() != Flowchart.Kind.DECISION) continue;
                if (vertex.kind() == Flowchart.Kind.JOIN && owner.kind() != StructuredDiagram.Kind.BLOCK) continue;
                int length = owner.end() - owner.start();
                if (length < smallest) { best = vertex.id(); smallest = length; }
            }
            return best;
        }

        void advance(int before, int after, boolean taken) {
            int from = vertexAt(before), to = vertexAt(after);
            if (from < 0 || to < 0) { transition = List.of(); return; }
            String role = program.controlRoles().get(before);
            String branch = null;
            if (("branch-true".equals(role) || "branch-false".equals(role))
                    && graph.vertex(from).kind() == Flowchart.Kind.DECISION
                    && ownsDecision(program.sourceMap().get(before), graph.vertex(from))) {
                boolean truth = "branch-true".equals(role) == taken;
                branch = truth ? "true" : "false";
            }
            if (from == to && branch == null) return;
            List<Flowchart.Edge> actual = path(from, to, branch);
            transition = actual;
        }

        boolean ownsDecision(SourceTrace trace, Flowchart.Vertex vertex) {
            return trace != null && Objects.equals(trace.location().file(), file)
                    && trace.location().startOffset() >= vertex.owner().start()
                    && trace.location().startOffset() < vertex.owner().end();
        }

        /** Paths may cross graph-only joins and statements which emit no machine instruction. */
        List<Flowchart.Edge> path(int from, int to, String branch) {
            if (from < 0 || to < 0) return List.of();
            record Visit(int vertex, List<Flowchart.Edge> edges) { }
            ArrayDeque<Visit> queue = new ArrayDeque<>();
            queue.add(new Visit(from, List.of()));
            Set<Integer> visited = new HashSet<>();
            visited.add(from);
            while (!queue.isEmpty()) {
                Visit current = queue.removeFirst();
                for (Flowchart.Edge edge : outgoing.getOrDefault(current.vertex(), List.of())) {
                    if (current.edges().isEmpty() && branch != null && !edge.label().equals(branch)) continue;
                    List<Flowchart.Edge> edges = new ArrayList<>(current.edges());
                    edges.add(edge);
                    if (edge.to() == to) return List.copyOf(edges);
                    if (!visited.add(edge.to()) || executable.contains(edge.to())) continue;
                    queue.addLast(new Visit(edge.to(), edges));
                }
            }
            return List.of();
        }
    }
}
