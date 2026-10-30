package machine;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable bytecode, opcode-start provenance, and the source snapshots used to compile it. */
public record CompilationResult(List<Integer> bytecode, Map<Integer, SourceTrace> sourceMap,
        int codeStart, Map<Path, String> sources) {
    public CompilationResult {
        bytecode = List.copyOf(bytecode);
        sourceMap = Collections.unmodifiableMap(new LinkedHashMap<>(sourceMap));
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }
}
