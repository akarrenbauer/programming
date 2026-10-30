package machine;

import java.nio.file.Path;

/** A range in LF-normalized source; offsets are UTF-16, end exclusive, line/column one-based. */
public record SourceLocation(Path file, int startOffset, int endOffset, int line, int column) {
    public SourceLocation {
        if (startOffset < 0 || endOffset < startOffset || line < 1 || column < 1) {
            throw new IllegalArgumentException("Invalid source location");
        }
    }
}
