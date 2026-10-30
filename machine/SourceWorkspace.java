package machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Editable source buffers with an optional active main program.
 * Compilation reads these buffers; only explicit saves write them to disk.
 */
public final class SourceWorkspace {
    private final List<Document> documents = new ArrayList<>();
    private final Map<Path, Document> namedDocuments = new LinkedHashMap<>();
    private final Map<Path, Document> openedPaths = new LinkedHashMap<>();
    private Document mainDocument;
    private int untitledCount;

    private SourceWorkspace() {
        newDocument();
    }

    private SourceWorkspace(Path mainPath) throws IOException {
        Document document = loadDocument(mainPath);
        if (document.hasBeginMarker()) {
            mainDocument = document;
        }
    }

    /** Opens one file and activates it only when it contains a top-level begin: marker. */
    public static SourceWorkspace open(Path mainPath) throws IOException {
        return new SourceWorkspace(mainPath);
    }

    /** Creates an empty, unnamed buffer without selecting a main program or accessing files. */
    public static SourceWorkspace create() {
        return new SourceWorkspace();
    }

    /** Adds an empty draft without replacing the program compiled by this workspace. */
    public Document newDocument() {
        int number = ++untitledCount;
        Document document = new Document(null, "", number == 1 ? "Untitled" : "Untitled " + number);
        documents.add(document);
        return document;
    }

    public Document getMainDocument() {
        if (mainDocument != null && !mainDocument.hasBeginMarker()) {
            mainDocument = null;
        }
        return mainDocument;
    }

    /** Opens or reuses a source buffer without changing the active main program. */
    public Document openDocument(Path path) throws IOException {
        return loadDocument(path);
    }

    /** Selects an owned document containing a top-level entry marker as the main program. */
    public void setMainDocument(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("A source document is required for the main program");
        }
        requireOwned(document);
        if (!document.hasBeginMarker()) {
            throw new IllegalArgumentException("The main program must contain a top-level begin: marker");
        }
        mainDocument = document;
    }

    /** Returns an immutable snapshot in opening order. */
    public List<Document> getDocuments() {
        return Collections.unmodifiableList(new ArrayList<>(documents));
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
        Document main = getMainDocument();
        if (main == null) {
            throw new ParseException("No main program selected. Select a source document with a top-level begin: marker.");
        }
        return Compiler.compileWithSourceMap(source(main), path -> source(loadDocument(path)));
    }

    private static SourceDocument source(Document document) {
        return new SourceDocument(document.path, document.text);
    }

    public boolean isModified() {
        return documents.stream().anyMatch(Document::isModified);
    }

    /** Saves one owned document if modified; failed writes leave it modified. */
    public void save(Document document) throws IOException {
        requireOwned(document);
        if (document.isUntitled()) {
            throw new IOException("A source path is required to save " + document.getDisplayName());
        }
        if (document.isModified()) {
            write(document.path, document.text);
            document.savedText = document.text;
        }
    }

    /** Names and saves an unnamed document; failure leaves its buffer and identity intact. */
    public void saveAs(Document document, Path path) throws IOException {
        requireOwned(document);
        if (!document.isUntitled()) {
            throw new IllegalArgumentException("Document already has a source path; use save instead");
        }
        Path requested = Objects.requireNonNull(path, "path").toAbsolutePath();
        rejectOpenTarget(requested, document);
        Path canonical;
        try {
            canonical = savePath(requested);
        } catch (IOException error) {
            throw new IOException("Could not save source file " + requested + ": "
                + error.getMessage(), error);
        }
        rejectOpenTarget(canonical, document);
        write(canonical, document.text);
        document.path = canonical;
        document.savedText = document.text;
        namedDocuments.put(canonical, document);
        openedPaths.put(requested, document);
        openedPaths.put(canonical, document);
    }

    private void requireOwned(Document document) {
        Objects.requireNonNull(document, "document");
        if (!documents.contains(document)) {
            throw new IllegalArgumentException("Document does not belong to this workspace");
        }
    }

    private void rejectOpenTarget(Path path, Document document) throws IOException {
        Document existing = openedPaths.get(path);
        if (existing == null) {
            existing = namedDocuments.get(path);
        }
        if (existing != null && existing != document) {
            throw new IOException("Source file is already open in another document: " + path);
        }
    }

    private static Path savePath(Path requested) throws IOException {
        Path target = requested;
        List<Path> links = new ArrayList<>();
        while (true) {
            try {
                return target.toRealPath();
            } catch (NoSuchFileException missing) {
                // A missing leaf can be created, but its parent must already exist.
                Path parent = target.getParent().toRealPath();
                target = parent.resolve(target.getFileName());
                if (!Files.isSymbolicLink(target)) {
                    return target;
                }
                // Follow a dangling leaf symlink before choosing the new file's identity.
                if (links.contains(target)) {
                    throw new IOException("Symbolic link cycle at " + target);
                }
                links.add(target);
                Path linked = Files.readSymbolicLink(target);
                target = linked.isAbsolute() ? linked : parent.resolve(linked);
            }
        }
    }

    private static void write(Path path, String text) throws IOException {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IOException("Could not save source file " + path + ": "
                + error.getMessage(), error);
        }
    }

    /** Saves modified documents in opening order, stopping at the first write failure. */
    public void saveAll() throws IOException {
        for (Document document : documents) {
            if (document.isModified()) {
                save(document);
            }
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
            document = namedDocuments.get(canonical);
            if (document == null) {
                document = new Document(canonical, Files.readString(canonical, StandardCharsets.UTF_8), null);
                namedDocuments.put(canonical, document);
                documents.add(document);
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
        private Path path;
        private final String untitledName;
        private String text;
        private String savedText;

        private Document(Path path, String text, String untitledName) {
            this.path = path;
            this.untitledName = untitledName;
            this.text = text;
            this.savedText = text;
        }

        public Path getPath() {
            return path;
        }

        public boolean isUntitled() {
            return path == null;
        }

        public String getDisplayName() {
            return isUntitled() ? untitledName : path.getFileName().toString();
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        public boolean hasBeginMarker() {
            return SourceSyntax.hasBeginMarker(text);
        }

        public boolean isModified() {
            return !text.equals(savedText);
        }
    }
}
