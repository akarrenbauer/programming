package machine;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Drafts and sequential lesson progress; language unlocks are derived from the catalog. */
public final class GameProgress {
    private static final String VERSION = "2";
    private static final List<String> VERSION_ONE_FOUNDATIONS =
            List.of("first-steps", "countdown", "zero-test", "two-paths");
    private static final List<String> VERSION_ONE_LEVELS = List.of(
            "first-steps", "countdown", "zero-test", "two-paths", "equals-three", "mandatory-lap",
            "preserve-copy", "reset-scratch", "three-cells", "repeated-work", "literal-dispatch",
            "compose-helpers", "array-practice", "macro-practice", "specialization-practice", "import-practice",
            "preserving-add", "saturating-subtract", "multiply", "divmod", "gcd", "normalize-four",
            "decompose-four", "decode-four", "encode-four", "not-four", "negate-four", "add-four",
            "subtract-four", "extend-four", "transfer-eight");
    private final Path path;
    private final List<GameLevel> levels;
    private final Map<String, Integer> indexes = new LinkedHashMap<>();
    private final Map<String, String> drafts = new LinkedHashMap<>();
    private final Set<String> completed = new LinkedHashSet<>();
    private final Set<GameFeature> unreadGrammar = EnumSet.noneOf(GameFeature.class);
    private boolean unreadToolbox;
    private int selectedIndex;
    private GameTaskLanguage taskLanguage = GameTaskLanguage.fromLocale(Locale.getDefault(Locale.Category.DISPLAY));

    /** Loads an existing progress file without creating a directory or file. */
    public GameProgress(Path path, List<GameLevel> levels) throws IOException {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath();
        this.levels = List.copyOf(levels);
        if (this.levels.isEmpty()) {
            throw new IllegalArgumentException("The game needs at least one level");
        }
        for (int index = 0; index < this.levels.size(); index++) {
            String id = Objects.requireNonNull(this.levels.get(index).id(), "level id");
            if (id.isBlank() || indexes.putIfAbsent(id, index) != null) {
                throw new IllegalArgumentException("Level IDs must be nonempty and unique: " + id);
            }
        }
        load();
    }

    public int completedCount() {
        return completed.size();
    }

    public boolean isCompleted(String id) {
        return completed.contains(id);
    }

    /** Completed lessons and the first unfinished lesson can be selected. */
    public boolean isAvailable(int index) {
        return index >= 0 && index < levels.size()
                && (!GameWorld.usesDiagrams(levels.get(index)) || GameWorld.hasStructuredControls(features()))
                && (completed.contains(levels.get(index).id()) || index == firstUnfinished());
    }

    public Set<String> completedIds() {
        Set<String> snapshot = new LinkedHashSet<>();
        for (GameLevel level : levels) {
            if (completed.contains(level.id())) snapshot.add(level.id());
        }
        return Collections.unmodifiableSet(snapshot);
    }

    /** Never trusts persisted feature flags or a caller-supplied replacement level. */
    public Set<GameFeature> features() {
        EnumSet<GameFeature> features = EnumSet.noneOf(GameFeature.class);
        for (GameLevel level : levels) {
            if (completed.contains(level.id())) features.addAll(level.unlocks());
        }
        // Before clear-block was introduced, mandatory-lap awarded do/while.
        // Keep that earned tool without falsely completing the new brace exercise.
        if (indexes.containsKey("clear-block") && completed.contains("mandatory-lap")) {
            features.add(GameFeature.DO_WHILE);
        }
        return Collections.unmodifiableSet(features);
    }

    /** Unread notices describe earned tools; they never grant language features. */
    public Set<GameFeature> unreadGrammarFeatures() { return Set.copyOf(unreadGrammar); }
    public boolean hasUnreadToolbox() { return unreadToolbox; }
    public void markGrammarRead() { unreadGrammar.clear(); }
    public void markToolboxRead() { unreadToolbox = false; }

    public GameTaskLanguage taskLanguage() { return taskLanguage; }

    /** Saves with the rest of the profile; changing text never changes drafts or unlocks. */
    public void setTaskLanguage(GameTaskLanguage language) {
        taskLanguage = Objects.requireNonNull(language, "language");
    }

    public String draft(GameLevel level) {
        GameLevel known = levels.get(index(level.id()));
        return drafts.getOrDefault(known.id(), known.starter());
    }

    /** Drafts may be kept for any known lesson, including one not yet unlocked. */
    public void setDraft(String id, String source) {
        index(id);
        drafts.put(id, Objects.requireNonNull(source, "source"));
    }

    /** Records a checked solution; callers must validate it before completing a lesson. */
    public void complete(GameLevel level, String validatedSource) {
        int index = index(level.id());
        if (!isAvailable(index)) {
            throw new IllegalArgumentException("Finish earlier levels before " + level.id());
        }
        Objects.requireNonNull(validatedSource, "validatedSource");
        Set<GameFeature> before = features();
        drafts.put(levels.get(index).id(), validatedSource);
        if (completed.add(levels.get(index).id())) {
            Set<GameFeature> additions = EnumSet.noneOf(GameFeature.class);
            additions.addAll(features());
            additions.removeAll(before);
            unreadGrammar.addAll(additions);
            unreadToolbox |= !levels.get(index).rewards().isEmpty();
        }
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int index) {
        if (!isAvailable(index)) {
            throw new IllegalArgumentException("Level is not available: " + index);
        }
        selectedIndex = index;
    }

    /** Restarts the entire game, retaining the old in-memory state if saving fails. */
    public void reset() throws IOException {
        Set<String> previousCompleted = new LinkedHashSet<>(completed);
        int previousSelection = selectedIndex;
        Map<String, String> previousDrafts = new LinkedHashMap<>(drafts);
        Set<GameFeature> previousUnread = Set.copyOf(unreadGrammar);
        boolean previousToolbox = unreadToolbox;
        completed.clear();
        selectedIndex = 0;
        drafts.clear();
        unreadGrammar.clear();
        unreadToolbox = false;
        try {
            save();
        } catch (IOException | RuntimeException error) {
            completed.addAll(previousCompleted);
            selectedIndex = previousSelection;
            drafts.putAll(previousDrafts);
            unreadGrammar.addAll(previousUnread);
            unreadToolbox = previousToolbox;
            throw error;
        }
    }

    /** Replaces the saved state only after a complete UTF-8 properties file is written. */
    public void save() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("selected", levels.get(selectedIndex).id());
        properties.setProperty("task.language", taskLanguage.code());
        for (String id : completedIds()) {
            properties.setProperty("completed." + id, "true");
        }
        drafts.forEach((id, source) -> properties.setProperty("draft." + id, source));
        for (GameFeature feature : unreadGrammar) properties.setProperty("unread.grammar." + feature.name(), "true");
        properties.setProperty("unread.toolbox", Boolean.toString(unreadToolbox));

        Path parent = path.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + path.getFileName() + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Language game progress");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private int index(String id) {
        Integer index = indexes.get(id);
        if (index == null) {
            throw new IllegalArgumentException("Unknown level: " + id);
        }
        return index;
    }

    private int firstUnfinished() {
        for (int index = 0; index < levels.size(); index++) {
            if (!completed.contains(levels.get(index).id())) return index;
        }
        return levels.size();
    }

    private List<GameLevel> versionOneOrder() {
        if (!levels.get(0).id().equals("first-steps")
                || !indexes.keySet().containsAll(VERSION_ONE_FOUNDATIONS)) {
            return levels;
        }
        // Validate the published version-one order, excluding later additions.
        // Inserting a new exercise must not truncate otherwise valid old credit.
        List<GameLevel> legacy = new ArrayList<>();
        for (String id : VERSION_ONE_LEVELS) {
            if (indexes.containsKey(id)) legacy.add(levels.get(indexes.get(id)));
        }
        return legacy;
    }

    private void load() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (NoSuchFileException missing) {
            return;
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed game progress file: " + path, malformed);
        }
        String version = properties.getProperty("version");
        if (!VERSION.equals(version) && !"1".equals(version)) {
            throw new IOException("Unsupported game progress version: " + version);
        }
        GameTaskLanguage savedLanguage = GameTaskLanguage.fromCode(properties.getProperty("task.language"));
        if (savedLanguage != null) taskLanguage = savedLanguage;
        for (GameLevel level : levels) {
            String completion = properties.getProperty("completed." + level.id());
            if (completion != null && !completion.equals("true") && !completion.equals("false")) {
                throw new IOException("Invalid completion value for level " + level.id());
            }
            String draft = properties.getProperty("draft." + level.id());
            if (draft != null) {
                drafts.put(level.id(), draft);
            }
        }
        if ("1".equals(version)) {
            for (GameLevel level : versionOneOrder()) {
                if (!"true".equals(properties.getProperty("completed." + level.id()))) break;
                completed.add(level.id());
            }
        } else {
            // Valid migrated credits can have gaps in the reordered curriculum.
            for (GameLevel level : levels) {
                if ("true".equals(properties.getProperty("completed." + level.id()))) completed.add(level.id());
            }
        }
        selectedIndex = Math.min(firstUnfinished(), levels.size() - 1);
        Integer savedSelection = indexes.get(properties.getProperty("selected"));
        if (savedSelection != null && isAvailable(savedSelection)) {
            selectedIndex = savedSelection;
        }
        // Old saves have no notices. Ignore stale flags for tools that are not earned.
        for (GameFeature feature : features()) {
            if (Boolean.parseBoolean(properties.getProperty("unread.grammar." + feature.name()))) unreadGrammar.add(feature);
        }
        unreadToolbox = Boolean.parseBoolean(properties.getProperty("unread.toolbox"))
                && levels.stream().anyMatch(level -> completed.contains(level.id()) && !level.rewards().isEmpty());
    }
}
