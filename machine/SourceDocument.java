package machine;

import java.nio.file.Path;
import java.util.Objects;

/** A compilation snapshot; the resolver supplies its canonical file identity. */
public record SourceDocument(Path file, String text) {
    public SourceDocument {
        text = Objects.requireNonNull(text, "text").replace("\r\n", "\n").replace('\r', '\n');
    }
}
