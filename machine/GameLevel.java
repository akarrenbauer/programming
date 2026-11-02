package machine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A short programming challenge, its finite test contract, and the tools it earns. */
public record GameLevel(String id, String world, String title, String goal, String lesson,
        String starter, List<String> hints, Set<GameFeature> unlocks,
        Set<GameFeature> requiredFeatures, Map<String, String> rewards,
        List<TestCase> cases, int maxSteps) {
    public GameLevel {
        hints = List.copyOf(hints);
        unlocks = Set.copyOf(unlocks);
        requiredFeatures = Set.copyOf(requiredFeatures);
        rewards = Collections.unmodifiableMap(new LinkedHashMap<>(rewards));
        cases = List.copyOf(cases);
        if (cases.isEmpty() || maxSteps <= 0) {
            throw new IllegalArgumentException("A level needs test cases and a positive step limit");
        }
    }

    public record TestCase(Map<String, Integer> inputs, Map<String, Integer> expected) {
        public TestCase {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            expected = Collections.unmodifiableMap(new LinkedHashMap<>(expected));
        }
    }
}
