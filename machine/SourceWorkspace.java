package machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Editable source buffers for one main program and the imports it has opened.
 * Compilation reads these buffers; only explicit saves write them to disk.
 */
public final class SourceWorkspace {
    private final Map<Path, Document> documents = new LinkedHashMap<>();
    private final Map<Path, Document> openedPaths = new LinkedHashMap<>();
    private final Document mainDocument;

    private SourceWorkspace(Path mainPath) throws IOException {
        mainDocument = loadDocument(mainPath);
    }

    /** Opens just the main file, allowing it to be edited even if compilation fails. */
    public static SourceWorkspace open(Path mainPath) throws IOException {
        return new SourceWorkspace(mainPath);
    }

    public Document getMainDocument() {
        return mainDocument;
    }

    /** Returns an immutable snapshot in opening order, with the main file first. */
    public List<Document> getDocuments() {
        return Collections.unmodifiableList(new ArrayList<>(documents.values()));
    }

    /**
     * Compiles current buffers, opening any newly encountered imports lazily.
     * Import paths retain the compiler's working-directory-relative semantics.
     * Documents stay open even when an import is removed or compilation fails.
     */
    public List<Integer> compile() throws IOException, ParseException {
        return compileWithSourceMap().bytecode();
    }

    /** Compiles the buffers and retains locations in the exact displayed source snapshots. */
    public CompilationResult compileWithSourceMap() throws IOException, ParseException {
        return Compiler.compileWithSourceMap(source(mainDocument), path -> source(loadDocument(path)));
    }

    private static SourceDocument source(Document document) {
        return new SourceDocument(document.path, document.text);
    }

    public boolean isModified() {
        return documents.values().stream().anyMatch(Document::isModified);
    }

    /** Saves one owned document if modified; failed writes leave it modified. */
    public void save(Document document) throws IOException {
        Objects.requireNonNull(document, "document");
        if (documents.get(document.path) != document) {
            throw new IllegalArgumentException("Document does not belong to this workspace");
        }
        if (document.isModified()) {
            try {
                Files.writeString(document.path, document.text, StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IOException("Could not save source file " + document.path + ": "
                    + error.getMessage(), error);
            }
            document.savedText = document.text;
        }
    }

    /** Saves modified documents in opening order, stopping at the first write failure. */
    public void saveAll() throws IOException {
        for (Document document : documents.values()) {
            save(document);
        }
    }

    private Document loadDocument(Path path) throws IOException {
        Path requested = Objects.requireNonNull(path, "path").toAbsolutePath();
        Document document = openedPaths.get(requested);
        if (document != null) {
            return document;
        }
        try {
            // Resolve symlinks before '..'; lexical normalization can select another file.
            Path canonical = requested.toRealPath();
            document = documents.get(canonical);
            if (document == null) {
                document = new Document(canonical, Files.readString(canonical, StandardCharsets.UTF_8));
                documents.put(canonical, document);
            }
            // Remember requests so opened buffers survive deletion or changes to symlinks.
            openedPaths.put(requested, document);
            openedPaths.put(canonical, document);
        } catch (IOException error) {
            throw new IOException("Could not read source file " + requested + ": "
                + error.getMessage(), error);
        }
        return document;
    }

    public static final class Document {
        private final Path path;
        private String text;
        private String savedText;

        private Document(Path path, String text) {
            this.path = path;
            this.text = text;
            this.savedText = text;
        }

        public Path getPath() {
            return path;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        public boolean isModified() {
            return !text.equals(savedText);
        }
    }
}
