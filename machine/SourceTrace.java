package machine;

import java.util.List;
import java.util.Objects;

/** Original instruction location and its macro invocation chain, outermost first. */
public record SourceTrace(SourceLocation location, List<SourceLocation> callers) {
    public SourceTrace {
        Objects.requireNonNull(location, "location");
        callers = List.copyOf(callers);
    }
}
