package machine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Execution evidence for a submission; finite checks are not a mathematical proof. */
public record GameResult(boolean passed, String summary, List<CaseResult> cases, SourceLocation location) {
    public GameResult {
        cases = List.copyOf(cases);
    }

    public record CaseResult(Map<String, Integer> inputs, Map<String, Integer> expected,
            Map<String, Integer> actual, int steps, boolean passed, String message) {
        public CaseResult {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            expected = Collections.unmodifiableMap(new LinkedHashMap<>(expected));
            actual = Collections.unmodifiableMap(new LinkedHashMap<>(actual));
        }
    }
}
