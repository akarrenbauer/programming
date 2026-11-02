package machine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** World-one profiles and source-owned control-flow edges, compared with actual machine execution. */
public final class FlowchartTest {
    private static final Set<GameFeature> ALL = EnumSet.allOf(GameFeature.class);
    private static int passed;
    private static int failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) {
        check("legacy do labels and variables are profile-specific and edits preserve that profile", () -> {
            String source = "// old language\r\nvar do=2;\r\nbegin:\r\ndo: DEC do;\r\nJNZ do do;\r\nHALT;\r\n";
            StructuredDiagram document = StructuredDiagram.parse(source, LanguageVersion.BEFORE_DO_WHILE, true);
            expect(document.main().nodes().get(0).labels().equals("do:"), "legacy do label");
            document = document.edit(document.main().nodes().get(0), "NOP;");
            expect(document.source().equals(source.replace("DEC do;", "NOP;")), "only edited source changes");
            document = document.insert(document.main(), 0, ";");
            document = document.delete(document.main().nodes().get(0));
            expect(document.languageVersion() == LanguageVersion.BEFORE_DO_WHILE && document.allowsExplicitJumps(),
                    "insert/delete retain legacy jump profile");
            rejected(() -> StructuredDiagram.parse(source), "default structured parser still rejects legacy do");
            rejected(() -> StructuredDiagram.parse(source, LanguageVersion.STRUCTURED, true), "allowing JNZ does not unreserve do");
            rejected(() -> StructuredDiagram.parse("var x=1; begin: do { DEC x; } while(x!=0);", LanguageVersion.BEFORE_DO_WHILE, true),
                    "legacy do profile cannot parse post-tested loop syntax");
        });

        check("JNZ is opt-in while existing structured parser and validator defaults remain unchanged", () -> {
            String source = "var x=1; begin: loop: DEC x; JNZ x loop; HALT;";
            rejected(() -> StructuredDiagram.parse(source), "structured default rejects explicit JNZ");
            expect(!DiagramValidation.validate(source, ALL, Map.of()).valid(), "default validation remains structured");
            DiagramValidation.Result valid = DiagramValidation.validate(source, ALL, Map.of(), LanguageVersion.STRUCTURED, true);
            expect(valid.valid() && valid.diagram().allowsExplicitJumps(), "structured syntax can coexist with JNZ in world one");
            expect(valid.diagram().main().nodes().get(1).kind() == StructuredDiagram.Kind.STATEMENT, "no incompatible AST enum case");
        });

        check("backward JNZ branches are decisions with true target and false fallthrough", () -> {
            String source = "var n=3; var out=0; begin: loop: INC out; DEC n; JNZ n loop; HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex jump = vertexFor(flow, "JNZ n loop;");
            expect(jump.kind() == Flowchart.Kind.DECISION && jump.text().equals("n != 0"), "jump condition");
            expect(edge(flow, jump.id(), "true").to() == flow.labels().get("loop"), "backward edge resolves label");
            expect(flow.vertex(edge(flow, jump.id(), "false").to()).kind() == Flowchart.Kind.HALT, "zero falls through to HALT");
            expect(run(flow, Map.of()).get("out") == 3, "flow loop consumes input");
            compareMachine(flow, Map.of());
        });

        check("the exact MachineGUI begin jump resolves to the first instruction and matches the VM", () -> {
            String source = "var x = 5;\nbegin:\n\tDEC x;\n\tJNZ x begin;\n\tHALT;";
            SourceWorkspace workspace = SourceWorkspace.create();
            SourceWorkspace.Document selected = workspace.getDocuments().get(0);
            selected.setText(source);
            DiagramValidation.Result checked = DiagramValidation.validateStandalone(workspace, selected, source, true);
            expect(checked.valid(), checked.message());
            Flowchart flow = Flowchart.from(checked.diagram());
            Flowchart.Vertex first = vertexFor(flow, "DEC x;"), jump = vertexFor(flow, "JNZ x begin;");
            expect(flow.labels().get("begin") == first.id() && flow.jumpTarget(jump).id() == first.id(),
                    "built-in begin reaches the first instruction's incoming junction");
            expect(flow.isImplicitBeginTarget(first.id()) && !flow.isImplicitBeginTarget(0)
                            && flow.vertex(flow.jumpTarget(jump).id()).kind() != Flowchart.Kind.START,
                    "the return arrow never targets the START oval");
            expect(flow.beginSpan().text(source).equals("begin:") && flow.beginSpan().start() == source.indexOf("begin:"),
                    "read-only built-in annotation links to the exact source entry marker");
            expect(flow.labelInfo("begin") == null && flow.labelDeclarations().isEmpty(),
                    "implicit entry is not misrepresented as an editable statement label");
            expect(!flow.isForwardJump(jump) && flow.isJumpRoute(edge(flow, jump.id(), "true")), "begin is a real backward branch");
            expect(run(flow, Map.of()).get("x") == 0 && flow.document().source().equals(source), "flowchart clears five without rewriting source");
            compareMachine(flow, Map.of());
        });

        check("begin preserves source entry semantics before NOP empty statements and empty blocks", () -> {
            for (String prefix : List.of("", "NOP;\n", ";\n", "{}\n", "empty: {}\n")) {
                String source = "var x=2;\nbegin:\n" + prefix + "DEC x;\nJNZ x begin;\nHALT;\n";
                Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
                StructuredDiagram.Node first = flow.document().main().nodes().get(0);
                Flowchart.Vertex jump = vertexFor(flow, "JNZ x begin;");
                expect(flow.jumpTarget(jump).id() == flow.entryVertex(first) && flow.isImplicitBeginTarget(flow.entryVertex(first)),
                        "implicit entry is the first source-owned entry even for " + prefix);
                compareMachine(flow, Map.of("x", 0));
                compareMachine(flow, Map.of("x", 2));
            }
            Flowchart empty = graph("// empty main\nbegin:", LanguageVersion.STRUCTURED);
            expect(empty.vertex(empty.labels().get("begin")).kind() == Flowchart.Kind.END
                            && edge(empty, 0, "").to() == empty.labels().get("begin"), "empty main has its ordinary end target");
            Flowchart helper = Flowchart.from(StructuredDiagram.parseDocument("void bump(x) { INC x; }\n", LanguageVersion.STRUCTURED, true));
            expect(!helper.labels().containsKey("begin") && helper.beginSpan() == null
                            && helper.vertices().stream().noneMatch(vertex -> helper.isImplicitBeginTarget(vertex.id())),
                    "helper-only documents never acquire a nonexistent begin label");
        });

        check("retargeting to and from implicit begin preserves source trivia and no-op drops", () -> {
            String source = "// begin: inside a comment\r\nvar x=0;\r\nbegin:\r\nDEC x;\r\nJNZ // keep command\r\n x // keep operand\r\n begin;\r\nfinish: HALT;\r\n";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex first = vertexFor(flow, "DEC x;");
            Flowchart.Vertex jump = flow.vertices().stream().filter(vertex -> flow.jumpInfo(vertex) != null).findFirst().orElseThrow();
            expect(flow.beginSpan().start() == source.lastIndexOf("begin:"), "comments cannot impersonate the real entry marker");
            expect(flow.retargetJump(jump.id(), first.id()).equals(source), "dropping existing begin head back on its junction is byte-for-byte unchanged");
            String moved = flow.retargetJump(jump.id(), flow.labels().get("finish"));
            expect(moved.equals(source.replace("finish: HALT;", "begin : finish: HALT;")), "moving begin adds a legal declaration while preserving the JNZ operand");
            Flowchart changed = graph(moved, LanguageVersion.STRUCTURED);
            String restored = changed.retargetJump(jump.id(), first.id());
            expect(restored.equals(source.replace("finish: HALT;", "  finish: HALT;")), "moving back removes rebinding tokens and preserves their surrounding whitespace");
            expect(graph(restored, LanguageVersion.STRUCTURED).isImplicitBeginTarget(first.id()), "structural begin becomes effective again");
            StructuredDiagram candidate = StructuredDiagram.parse(moved, LanguageVersion.STRUCTURED, true);
            expect(Flowchart.retargetJump(candidate, candidate.main().nodes().get(1).start(), candidate.main().nodes().get(0).start()).equals(restored),
                    "candidate-based drops share the same implicit entry binding");
            Flowchart self = graph("var x=0; begin: JNZ x begin; HALT;", LanguageVersion.STRUCTURED);
            Flowchart.Vertex selfJump = vertexFor(self, "JNZ x begin;");
            expect(self.jumpTarget(selfJump).id() == selfJump.id() && !self.isForwardJump(selfJump)
                            && self.retargetJump(selfJump.id(), selfJump.id()).equals(self.document().source()), "first-instruction self jump keeps begin and backward polarity");
            compareMachine(changed, Map.of());
            compareMachine(self, Map.of());
        });

        check("spaced begin rebinding relocates both jumps and program startup exactly as the compiler", () -> {
            String source = "var n=2; var out=0; begin: INC out; begin : DEC n; JNZ n begin; HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex initial = vertexFor(flow, "INC out;"), rebound = vertexFor(flow, "DEC n;"), jump = vertexFor(flow, "JNZ n begin;");
            expect(flow.labels().get("begin") == rebound.id() && edge(flow, 0, "").to() == rebound.id()
                            && flow.jumpTarget(jump).id() == rebound.id(), "last begin declaration controls initial PC and later jumps");
            expect(!flow.isImplicitBeginTarget(initial.id()) && !flow.isImplicitBeginTarget(rebound.id())
                            && flow.labelInfo("begin").declarationSpan().text(source).equals("begin :"), "explicit rebinding is editable source metadata");
            expect(run(flow, Map.of()).get("out") == 0, "startup skips instructions preceding the rebound entry");
            compareMachine(flow, Map.of());
            expect(flow.retargetJump(jump.id(), rebound.id()).equals(source), "drop onto current explicit begin keeps its source");
            String firstTarget = flow.retargetJump(jump.id(), initial.id());
            expect(!firstTarget.contains("begin :") && firstTarget.contains("JNZ n begin;"),
                    "restoring the initial entry removes explicit begin rebinding without rewriting its users");
            compareMachine(graph(firstTarget, LanguageVersion.STRUCTURED), Map.of());
            Flowchart nested = graph("var n=2; var out=0; begin: INC out; begin : NOP; { begin : DEC n; } JNZ n begin; HALT;", LanguageVersion.STRUCTURED);
            expect(nested.labelDeclarations().stream().filter(label -> label.name().equals("begin")).count() == 2
                            && nested.jumpTarget(vertexFor(nested, "JNZ n begin;")).owner().editText().equals("DEC n;"),
                    "last nested source declaration wins over earlier begin declarations");
            compareMachine(nested, Map.of());
        });

        check("forward labels can reach code after HALT while HALT itself never falls through", () -> {
            String source = "var n=1; var out=0; begin: JNZ n after; HALT; after: INC out;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            for (Flowchart.Vertex vertex : flow.vertices()) {
                if (vertex.kind() == Flowchart.Kind.HALT) expect(outgoing(flow, vertex.id()).isEmpty(), "HALT has no outgoing edge");
            }
            expect(flow.vertices().get(flow.vertices().size() - 1).kind() == Flowchart.Kind.END, "implicit fallthrough ends explicitly");
            expect(run(flow, Map.of("n", 1)).get("out") == 1, "forward branch skips HALT");
            expect(run(flow, Map.of("n", 0)).get("out") == 0, "zero branch stops at HALT");
            compareMachine(flow, Map.of("n", 0));
            compareMachine(flow, Map.of("n", 1));
            Flowchart halted = graph("begin: HALT;", LanguageVersion.STRUCTURED);
            expect(halted.vertices().stream().noneMatch(v -> v.kind() == Flowchart.Kind.END), "explicit HALT does not add a disconnected END");
        });

        check("labels have compiler-wide main scope across nested and empty blocks", () -> {
            String source = "var take=1; var out=0; begin: JNZ take inside; if(take==0) { inside: INC out; } empty: {} HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            expect(flow.vertex(flow.labels().get("empty")).kind() == Flowchart.Kind.JOIN, "empty labeled block has a target");
            expect(flow.vertex(flow.labels().get("empty")).owner().kind() == StructuredDiagram.Kind.BLOCK,
                    "block label remains owned and editable even with no child");
            compareMachine(flow, Map.of("take", 0));
            compareMachine(flow, Map.of("take", 1));
            Flowchart repeated = graph("var x=0; begin: same: { same: NOP; } HALT;", LanguageVersion.STRUCTURED);
            expect(repeated.vertex(repeated.labels().get("same")).kind() == Flowchart.Kind.PROCESS,
                    "last source declaration wins, including a nested declaration");
            Flowchart aliases = graph("begin: one: two: NOP; HALT;", LanguageVersion.STRUCTURED);
            expect(aliases.labels().get("one").equals(aliases.labels().get("two")), "multiple labels share statement entry");
        });

        check("if alternatives join and while and do/while have correct loop entry and return edges", () -> {
            String source = "var n=2; var out=0; begin: if(n==0) {INC out;} else {NOP;} while(n!=0) {DEC n; INC out;} do {INC out;} while(n!=0); HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            compareMachine(flow, Map.of("n", 0));
            compareMachine(flow, Map.of("n", 2));
            StructuredDiagram.Node post = flow.document().main().nodes().get(2);
            List<Flowchart.Vertex> postVertices = flow.vertices().stream().filter(v -> v.owner() == post).toList();
            expect(postVertices.get(0).kind() == Flowchart.Kind.JOIN && postVertices.get(1).kind() == Flowchart.Kind.DECISION,
                    "do/while entry precedes body and footer decision");
            Flowchart.Vertex body = flow.vertices().stream().filter(v -> v.owner() == post.body().nodes().get(0)).findFirst().orElseThrow();
            expect(body.id() > postVertices.get(0).id() && body.id() < postVertices.get(1).id(), "post-tested body visually precedes decision");
            expect(edge(flow, postVertices.get(1).id(), "true").to() == postVertices.get(0).id(), "post-test true repeats body");
            Flowchart empty = graph("var n=0; begin: while(n!=0) {} do {} while(n!=0); if(n==0) {} else {} HALT;", LanguageVersion.STRUCTURED);
            compareMachine(empty, Map.of());
            Flowchart.Vertex pretest = empty.vertices().stream().filter(v -> v.kind() == Flowchart.Kind.DECISION).findFirst().orElseThrow();
            expect(edge(empty, pretest.id(), "true").to() == pretest.id(), "empty while true repeats the test");
        });

        check("JNZ and label edits keep comments and rewire the graph from new source", () -> {
            String source = "var x=1; begin: first: NOP; second: NOP; // keep this\nJNZ x first; HALT;";
            StructuredDiagram document = StructuredDiagram.parse(source, LanguageVersion.STRUCTURED, true);
            StructuredDiagram.Node jump = document.main().nodes().get(2);
            document = document.edit(jump, "JNZ x second;");
            expect(document.source().equals(source.replace("JNZ x first;", "JNZ x second;")), "unrelated formatting and comment preserved");
            Flowchart flow = Flowchart.from(document);
            Flowchart.Vertex decision = vertexFor(flow, "JNZ x second;");
            expect(edge(flow, decision.id(), "true").to() == flow.labels().get("second"), "edit changes actual edge target");
            document = document.replace(document.main().nodes().get(1), "other: NOP;");
            expect(!DiagramValidation.validate(document.source(), ALL, Map.of(), LanguageVersion.STRUCTURED, true).valid(),
                    "renaming only the target invalidates the unresolved jump");
        });

        check("jump operands targets and label prefixes expose exact original source spans", () -> {
            String prefix = "first // declaration comment\r\n: alias:\t// fake: is only a comment\r\n";
            String source = "// JNZ ignored target;\r\nvar x=1; var y=0; begin:\r\n" + prefix
                    + "NOP;\r\nJNZ // command comment\r\n x\t// operand comment\r\n first;\r\nHALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex jump = flow.vertices().stream().filter(v -> flow.jumpInfo(v) != null).findFirst().orElseThrow();
            Flowchart.JumpInfo info = flow.jumpInfo(jump.id());
            expect(info.variable().equals("x") && info.targetLabel().equals("first"), "canonical presentation values");
            expect(info.operand().text(source).equals("x") && info.target().text(source).equals("first"), "precise editable tokens");
            expect(info.operand().start() == source.indexOf(" x\t") + 1 && info.target().start() == source.lastIndexOf("first"),
                    "comments and CRLF do not shift absolute offsets");
            StructuredDiagram.Node target = flow.vertex(flow.labels().get("first")).owner();
            expect(flow.labelPrefix(target).text(source).equals(prefix), "prefix contains all declarations and trivia");
            expect(flow.statementSpan(target).text(source).equals("NOP;"), "process text excludes every label");
            expect(flow.labelDeclarations().stream().map(Flowchart.LabelInfo::name).toList().equals(List.of("first", "alias")),
                    "comments cannot introduce spurious labels");
            Flowchart.LabelInfo label = flow.labelInfo("first");
            expect(label.nameSpan().text(source).equals("first")
                            && label.declarationSpan().text(source).equals("first // declaration comment\r\n:"),
                    "single-name and complete-declaration spans preserve intervening trivia");
            expect(flow.isJumpEdge(edge(flow, jump.id(), "true")) && !flow.isJumpEdge(edge(flow, jump.id(), "false")),
                    "only the canonical nonzero edge is the explicit jump");
            String changed = info.operand().replace(source, "y");
            expect(changed.equals(source.substring(0, info.operand().start()) + "y" + source.substring(info.operand().end())),
                    "operand editing changes no label target comment or surrounding whitespace");
            Flowchart edited = graph(changed, LanguageVersion.STRUCTURED);
            Flowchart.JumpInfo editedInfo = edited.jumpInfo(jump.id());
            expect(editedInfo.variable().equals("y") && editedInfo.targetLabel().equals("first"), "operand edit reparses metadata");
            String renamed = label.nameSpan().replace(source, "renamed");
            expect(renamed.contains("renamed // declaration comment\r\n:") && renamed.contains(" first;"),
                    "label-name edits preserve annotation and do not silently alter jump references");
        });

        check("array jump metadata handles comments leading zeros and adjacent target tokens", () -> {
            String operand = "cells // array comment\n [ 02 ]";
            String source = "var cells[3]=1; begin: JNZ " + operand + "target; target: HALT;";
            DiagramValidation.Result checked = DiagramValidation.validate(source, ALL, Map.of(), LanguageVersion.STRUCTURED, true);
            expect(checked.valid(), "the compiler accepts an identifier immediately after the closing array bracket");
            Flowchart flow = Flowchart.from(checked.diagram());
            Flowchart.Vertex jump = flow.vertices().stream().filter(v -> flow.jumpInfo(v) != null).findFirst().orElseThrow();
            Flowchart.JumpInfo info = flow.jumpInfo(jump);
            expect(info.variable().equals("cells[02]") && info.operand().text(source).equals(operand), "canonical and raw indexed operands");
            expect(info.target().text(source).equals("target") && info.target().start() == info.operand().end(), "adjacent target span");
            expect(edge(flow, jump.id(), "true").to() == flow.labelInfo("target").vertex(), "array JNZ still resolves the same true edge");
        });

        check("structured comparison metadata separates operands and canonical operators without rewriting source", () -> {
            String source = "var cells[2]=0; begin:\nchosen: if( cells // operand\n [01] \t!= // operator\n 00 ) {}\n"
                    + "while (3 == 03) {}\ndo {} while(cells[00] != 0);\nHALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            List<Flowchart.Vertex> decisions = flow.vertices().stream().filter(v -> v.kind() == Flowchart.Kind.DECISION).toList();
            expect(decisions.size() == 3, "if while and do/while each expose a comparison");
            Flowchart.ComparisonInfo first = flow.comparisonInfo(decisions.get(0));
            expect(first.operandText().equals("cells[01]") && first.operand().text(source).equals("cells // operand\n [01]"),
                    "operand span includes its original internal comments and spaces");
            expect(first.operator().text(source).equals("!=") && first.canonicalNotEqual()
                            && first.rightText().equals("00") && first.right().text(source).equals("00"), "nonzero comparator and literal spelling");
            Flowchart.ComparisonInfo second = flow.comparisonInfo(decisions.get(1).id());
            expect(!second.canonicalNotEqual() && second.operator().text(source).equals("==")
                            && second.operandText().equals("3") && second.rightText().equals("03"), "literal equality remains canonical");
            Flowchart.ComparisonInfo post = flow.comparisonInfo(decisions.get(2));
            expect(post.owner().kind() == StructuredDiagram.Kind.DO_WHILE && post.canonicalNotEqual(), "post-test footer metadata");
            expect(flow.jumpInfo(decisions.get(0)) == null && !flow.isJumpEdge(edge(flow, decisions.get(0).id(), "true")),
                    "structured branch edges are not explicit JNZ routes");
            List<Flowchart.Edge> unchanged = flow.edges();
            expect(flow.document().source().equals(source) && flow.edges().equals(unchanged),
                    "reading presentation metadata cannot change source or true/false semantics");
            expect(flow.statementSpan(first.owner()).text(source).startsWith("if("), "control statement span skips its label");
            expect(flow.labelPrefix(second.owner()).start() == flow.labelPrefix(second.owner()).end(), "unlabeled control gets an empty insertion span");
            String changed = second.operand().replace(source, "4");
            expect(changed.equals(source.replace("while (3 == 03)", "while (4 == 03)")), "editing a value preserves the operator and right literal");
        });

        check("label metadata retains aliases and source order while resolving the final duplicate", () -> {
            String source = "var x=0; begin: same: { same // inner\n: alias: NOP; } JNZ x same; HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            expect(flow.labelDeclarations().stream().map(Flowchart.LabelInfo::name).toList().equals(List.of("same", "same", "alias")),
                    "all declarations remain available for source editing");
            Flowchart.LabelInfo winner = flow.labelInfo("same");
            expect(winner == flow.labelDeclarations().get(1) && winner.vertex() == flow.labels().get("same"),
                    "incoming jump junction uses the final compiler-visible declaration");
            expect(winner.vertex() == flow.labelInfo("alias").vertex(), "aliases share the incoming junction");
            expect(winner.nameSpan().start() == source.indexOf("same // inner"), "winning label location is exact");
            StructuredDiagram.Node halt = flow.document().main().nodes().get(2);
            expect(flow.labelPrefix(halt).text(source).isEmpty() && flow.statementSpan(halt).text(source).equals("HALT;"),
                    "unlabeled statements do not acquire a duplicate label field in their text");
        });

        check("jump direction is derived from canonical destinations including self jumps", () -> {
            String source = "var x=0; begin: first: NOP; self: JNZ x self; JNZ x first; JNZ x last; last: HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex self = vertexFor(flow, "JNZ x self;");
            Flowchart.Vertex backward = vertexFor(flow, "JNZ x first;");
            Flowchart.Vertex forward = vertexFor(flow, "JNZ x last;");
            expect(!flow.isForwardJump(self) && flow.jumpTarget(self).id() == self.id(), "self junction is backward");
            expect(!flow.isForwardJump(backward.id()) && flow.jumpTarget(backward).id() < backward.id(), "earlier target is backward");
            expect(flow.isForwardJump(forward) && flow.jumpTarget(forward.id()).id() > forward.id(), "later target is forward");
            for (Flowchart.Vertex jump : List.of(self, backward, forward)) {
                expect(flow.jumpTarget(jump).id() == edge(flow, jump.id(), "true").to(), "nonzero remains the canonical true edge");
            }
            expect(flow.document().source().equals(source), "direction presentation never rewrites source");
        });

        check("moving a label keeps jump operands and unrelated aliases intact", () -> {
            String source = "var x=1; var out=0; begin: before: INC out; self: JNZ x before; alias: after: HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex jump = vertexFor(flow, "JNZ x before;");
            expect(flow.retargetJump(jump.id(), flow.labels().get("before")).equals(source), "same target retains source byte for byte");
            String forwardSource = flow.retargetJump(jump.owner(), flow.vertex(flow.labels().get("after")).owner());
            expect(forwardSource.equals(source.replace("before:", "").replace("alias:", "before: alias:")), "the original label moves alongside destination aliases");
            Flowchart forward = graph(forwardSource, LanguageVersion.STRUCTURED);
            expect(forward.isForwardJump(jump.id()) && run(forward, Map.of()).get("out") == 1, "retargeted jump skips the old loop");
            compareMachine(forward, Map.of());
            String selfSource = flow.retargetJump(jump.id(), jump.id());
            expect(selfSource.equals(source.replace("before:", "").replace("self:", "before: self:")), "self destination gains the moved label and keeps its alias");
            Flowchart self = graph(selfSource, LanguageVersion.STRUCTURED);
            expect(!self.isForwardJump(jump.id()) && self.jumpTarget(jump.id()).id() == jump.id(), "self destination remains exact");
            compareMachine(self, Map.of("x", 0));
            Flowchart alias = graph(forwardSource.replace("JNZ x before;", "JNZ x after;"), LanguageVersion.STRUCTURED);
            expect(alias.retargetJump(jump.id(), alias.labels().get("after")).equals(alias.document().source()),
                    "an existing non-first alias is kept when it already reaches the requested destination");
        });

        check("label relocation preserves jump text and all unrelated bytes for backward forward and self drops", () -> {
            String source = "var x=0;\r\nbegin:\r\nDEC x; // keep this\r\nJNZ // command\r\n x // operand\r\n old;\r\nNOP;\r\nold: HALT;\r\n";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex jump = flow.vertices().stream().filter(v -> flow.jumpInfo(v) != null).findFirst().orElseThrow();
            for (String target : List.of("DEC x;", "NOP;", jump.owner().editText())) {
                Flowchart.Vertex destination = vertexFor(flow, target);
                String changed = flow.retargetJump(jump.id(), destination.id());
                String expected = source.replace("old:", "").replace(target, "old: " + target);
                expect(changed.equals(expected), "only the label declaration moves for " + target);
                DiagramValidation.Result validated = DiagramValidation.validate(changed, ALL, Map.of(), LanguageVersion.STRUCTURED, true);
                expect(validated.valid(), "atomic source compiles: " + validated.message());
                Flowchart edited = Flowchart.from(validated.diagram());
                expect(edited.jumpTarget(jump.id()).id() == destination.id(), "vertex topology is preserved across prefix insertion");
                compareMachine(edited, Map.of());
            }
        });

        check("moving a duplicate name removes superseded declarations and preserves unrelated aliases", () -> {
            String source = "var x=0; var jumpTarget=0; begin: NOP; same: DEC x; JNZ x same; same: HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex jump = vertexFor(flow, "JNZ x same;");
            Flowchart.Vertex target = vertexFor(flow, "DEC x;");
            String changed = flow.retargetJump(jump.id(), target.id());
            expect(changed.equals(source.replace("same: DEC x;", "same:  DEC x;").replace("same: HALT;", " HALT;")),
                    "the final declaration moves backwards and its superseded duplicate is removed");
            Flowchart edited = graph(changed, LanguageVersion.STRUCTURED);
            expect(edited.jumpTarget(jump.id()).id() == target.id()
                            && edited.labels().get("same") == target.id()
                            && edited.labelDeclarations().stream().filter(label -> label.name().equals("same")).count() == 1,
                    "all references use the one moved declaration");
            compareMachine(edited, Map.of());
            Flowchart aliases = graph(source.replace("same: DEC x;", "same: usable: DEC x;"), LanguageVersion.STRUCTURED);
            String movedAliases = aliases.retargetJump(jump.id(), target.id());
            expect(movedAliases.contains("JNZ x same;") && movedAliases.contains("usable:"), "destination alias remains while jump operand stays same");
            Flowchart nested = graph("var x=0; begin: NOP; same: { same: NOP; } JNZ x same; HALT;", LanguageVersion.STRUCTURED);
            StructuredDiagram.Node block = nested.document().main().nodes().get(1);
            String outer = nested.retargetJump(nested.document().main().nodes().get(2), block);
            Flowchart outerFlow = graph(outer, LanguageVersion.STRUCTURED);
            expect(outer.contains("JNZ x same;") && outerFlow.labels().get("same") == outerFlow.entryVertex(outerFlow.document().main().nodes().get(1))
                            && outerFlow.labelDeclarations().size() == 1,
                    "removing nested duplicates keeps a moved outer declaration effective");
        });

        check("coherent pending label renames move atomically while unresolved renames keep drafts untouched", () -> {
            String pending = "var x=0; var y=0; begin: renamed: NOP; JNZ y renamed; HALT;";
            StructuredDiagram candidate = StructuredDiagram.parse(pending, LanguageVersion.BEFORE_DO_WHILE, true);
            StructuredDiagram.Node jump = candidate.main().nodes().get(1), target = candidate.main().nodes().get(2);
            String changed = Flowchart.retargetJump(candidate, jump.start(), target.start());
            expect(changed.equals(pending.replace("renamed:", "").replace("HALT;", "renamed: HALT;")), "pending operand and label edits survive the move");
            expect(DiagramValidation.validate(changed, ALL, Map.of(), LanguageVersion.BEFORE_DO_WHILE, true).valid(),
                    "repaired candidate compiles in its original language profile");
            expect(candidate.source().equals(pending), "candidate snapshot remains immutable");
            StructuredDiagram unresolved = StructuredDiagram.parse(pending.replace("JNZ y renamed;", "JNZ y old;"), LanguageVersion.BEFORE_DO_WHILE, true);
            try { Flowchart.retargetJump(unresolved, unresolved.main().nodes().get(1).start(), unresolved.main().nodes().get(2).start());
                throw new AssertionError("unresolved draft was silently rebound"); }
            catch (IllegalArgumentException expected) { expect(expected.getMessage().contains("match its label"), "unresolved rename diagnostic"); }
            try { Flowchart.retargetJump(candidate, jump.start() + 1, target.start()); throw new AssertionError("stale jump start accepted"); }
            catch (IllegalArgumentException expected) { expect(expected.getMessage().contains("JNZ"), "stale jump diagnostic"); }
            try { Flowchart.retargetJump(candidate, jump.start(), candidate.preamble().nodes().get(0).start()); throw new AssertionError("preamble target accepted"); }
            catch (IllegalArgumentException expected) { expect(expected.getMessage().contains("statement entry"), "non-main target diagnostic"); }
        });

        check("moving a shared destination redirects every reference while both sets of aliases remain", () -> {
            String source = "var x=1; var out=0; begin: JNZ x shared; JNZ out shared; oldAlias: shared: INC out; JNZ x stop; destination: stop: HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex selected = flow.vertices().stream().filter(vertex -> flow.jumpInfo(vertex) != null
                    && flow.jumpInfo(vertex).targetLabel().equals("shared")).findFirst().orElseThrow();
            String changed = flow.retargetJump(selected.id(), flow.labels().get("destination"));
            expect(changed.equals(source.replace("shared:", "").replace("destination:", "shared: destination:")),
                    "only the shared declaration moves, with no reference token rewrites");
            Flowchart moved = graph(changed, LanguageVersion.STRUCTURED);
            for (Flowchart.Vertex vertex : moved.vertices()) if (moved.jumpInfo(vertex) != null
                    && moved.jumpInfo(vertex).targetLabel().equals("shared")) {
                expect(moved.jumpTarget(vertex).id() == moved.labels().get("destination"), "every same-name arrow follows the moved declaration");
            }
            expect(moved.labels().get("oldAlias").equals(flow.labels().get("oldAlias"))
                            && moved.labels().get("destination").equals(moved.labels().get("stop")), "unrelated origin and target aliases are unchanged");
            expect(run(flow, Map.of()).get("out") == 1 && run(moved, Map.of()).get("out") == 0, "both old and moved programs have the intended different behavior");
            compareMachine(flow, Map.of()); compareMachine(moved, Map.of());
        });

        check("duplicate relocation preserves comment bytes and moves the winning declaration's internal trivia", () -> {
            String source = "var x=0;\r\nbegin:\r\nfirst: NOP;\r\nshared // old shadow\r\n : middle: NOP;\r\n"
                    + "JNZ // untouched jump\r\n x shared;\r\nshared // winning label\r\n : tail: HALT; // keep trailing\r\n";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex jump = flow.vertices().stream().filter(vertex -> flow.jumpInfo(vertex) != null).findFirst().orElseThrow();
            String changed = flow.retargetJump(jump.id(), flow.labels().get("first"));
            String expected = source.replace("shared // old shadow\r\n :", " // old shadow\r\n ")
                    .replace("shared // winning label\r\n :", "")
                    .replace("first: NOP;", "shared // winning label\r\n : first: NOP;");
            expect(changed.equals(expected), "shadow comments remain in place, winning internal comment moves, all CRLF and jump bytes survive");
            Flowchart moved = graph(changed, LanguageVersion.STRUCTURED);
            expect(moved.labelDeclarations().stream().filter(label -> label.name().equals("shared")).count() == 1
                            && moved.jumpTarget(jump.id()).id() == moved.labels().get("first"), "backward relocation remains the effective compiler binding");
            compareMachine(moved, Map.of());
        });

        check("restoring begin removes all explicit rebindings while preserving comments and shared users", () -> {
            String source = "var n=2; begin: NOP; begin // shadow\n : NOP; { begin // winner\n : DEC n; } JNZ n begin; JNZ n begin; HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex jump = flow.vertices().stream().filter(vertex -> flow.jumpInfo(vertex) != null).findFirst().orElseThrow();
            String changed = flow.retargetJump(jump.id(), flow.entryVertex(flow.document().main().nodes().get(0)));
            expect(changed.contains("// shadow\n") && changed.contains("// winner\n")
                            && changed.contains("JNZ n begin; JNZ n begin;"), "begin comments and all references remain unchanged");
            Flowchart restored = graph(changed, LanguageVersion.STRUCTURED);
            expect(restored.labelInfo("begin") == null && restored.isImplicitBeginTarget(restored.labels().get("begin")),
                    "the mandatory structural header becomes the sole begin binding");
            expect(edge(restored, 0, "").to() == restored.labels().get("begin"), "startup also returns to first instruction");
            compareMachine(restored, Map.of());
        });

        check("unlabeled loop lowering creates a dedicated fresh label without moving startup or destination aliases", () -> {
            String source = "var n=2; var jumpTarget=0; begin: do { DEC n; } while(n!=0); finish: HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            StructuredDiagram.Node loop = flow.document().main().nodes().get(0);
            String changed = flow.retargetJump(loop, flow.document().main().nodes().get(1));
            Flowchart lowered = graph(changed, LanguageVersion.STRUCTURED);
            Flowchart.JumpInfo jump = lowered.vertices().stream().map(lowered::jumpInfo).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            expect(jump.targetLabel().equals("jumpTarget2") && changed.contains("jumpTarget2: finish: HALT;"),
                    "new loop target avoids all identifiers and preserves the destination alias");
            expect(lowered.isImplicitBeginTarget(lowered.entryVertex(lowered.document().main().nodes().get(0)))
                            && run(lowered, Map.of()).get("n") == 1, "unlabeled lowering does not move the built-in startup destination");
            compareMachine(lowered, Map.of());
        });

        check("arrowheads only target source-representable entries including do bodies and empty blocks", () -> {
            Flowchart flow = graph("var x=0; begin: target: HALT; JNZ x target; if(x==0) {} while(x!=0) {} do {} while(x!=0); {}", LanguageVersion.STRUCTURED);
            Flowchart.Vertex jump = vertexFor(flow, "JNZ x target;");
            int rejected = 0;
            for (Flowchart.Vertex vertex : flow.vertices()) {
                boolean entry = vertex.owner() != null && flow.entryVertex(vertex.owner()) == vertex.id();
                expect(flow.canTarget(vertex) == entry && flow.canTarget(vertex.id()) == entry, "entry-only targetability");
                if (entry) {
                    String changed = flow.retargetJump(jump.id(), vertex.id());
                    Flowchart edited = graph(changed, LanguageVersion.STRUCTURED);
                    expect(edited.jumpTarget(jump.id()).id() == vertex.id(), "label reaches exactly the selected entry");
                } else {
                    try { flow.retargetJump(jump.id(), vertex.id()); throw new AssertionError("synthetic/footer target accepted"); }
                    catch (IllegalArgumentException expected) { ++rejected; }
                }
            }
            expect(rejected == 5, "START END IF continuation WHILE continuation and DO footer are excluded");
            expect(!flow.canTarget(-1) && !flow.canTarget(100000), "out-of-range targets are rejected");
            StructuredDiagram.Node foreign = StructuredDiagram.parse("begin: HALT;").main().nodes().get(0);
            expect(flow.entryVertex(foreign) == -1, "nodes from other snapshots have no entry in this graph");
        });

        check("do/while exposes a hidden body-entry junction and a JNZ-style return without changing source", () -> {
            String source = "var n=2; begin: JNZ n again; again: do { DEC n; } while(n!=0); HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            StructuredDiagram.Node loop = flow.document().main().nodes().get(1);
            Flowchart.Vertex footer = flow.vertices().stream().filter(v -> flow.loopJumpInfo(v) != null).findFirst().orElseThrow();
            Flowchart.LoopJumpInfo info = flow.loopJumpInfo(footer.id());
            Flowchart.Vertex entry = flow.jumpTarget(footer);
            expect(entry.kind() == Flowchart.Kind.JOIN && entry.text().isEmpty() && entry.owner() == loop,
                    "entry has no visible do box or synthetic process text");
            expect(info.owner() == loop && info.target() == flow.entryVertex(loop) && info.retargetable(),
                    "loop return retains its source owner and exact body-entry target");
            expect(flow.labels().get("again") == entry.id() && flow.jumpTarget(vertexFor(flow, "JNZ n again;")).id() == entry.id(),
                    "explicit jumps into labeled loops reach the body entry, not the trailing condition");
            expect(flow.isJumpRoute(edge(flow, footer.id(), "true")) && !flow.isJumpEdge(edge(flow, footer.id(), "true"))
                            && !flow.isJumpRoute(edge(flow, footer.id(), "false")), "return routing is separate from explicit source-token metadata");
            expect(!flow.isForwardJump(footer) && flow.canRetargetJump(footer.id()) && flow.jumpInfo(footer) == null,
                    "loop return is backward and editable without pretending it is already a JNZ source instruction");
            Flowchart.ComparisonInfo condition = flow.comparisonInfo(footer);
            expect(condition.operand().text(source).equals("n") && condition.operator().text(source).equals("!=")
                            && condition.owner() == loop, "footer linking and condition edits still use the structured source");
            expect(flow.document().source().equals(source) && flow.retargetJump(footer.id(), entry.id()).equals(source),
                    "rendering and dropping onto the original loop entry preserve every source byte");
            compareMachine(flow, Map.of("n", 0));
            compareMachine(flow, Map.of("n", 2));
        });

        check("moving a do/while return lowers only that loop and follows the new destination", () -> {
            String source = "var n=2; var out=0; begin: before: NOP; alias: do { DEC n; INC out; } while(n!=0); finish: HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            Flowchart.Vertex footer = flow.vertices().stream().filter(v -> flow.loopJumpInfo(v) != null).findFirst().orElseThrow();
            String moved = flow.retargetJump(footer.id(), flow.labels().get("finish"));
            DiagramValidation.Result checked = DiagramValidation.validate(moved, ALL, Map.of(), LanguageVersion.STRUCTURED, true);
            expect(checked.valid(), checked.message());
            Flowchart edited = Flowchart.from(checked.diagram());
            expect(edited.document().main().nodes().get(1).kind() == StructuredDiagram.Kind.BLOCK
                            && edited.document().main().nodes().get(1).labels().isEmpty(), "original body remains in a bare block while its label moves");
            Flowchart.Vertex jump = edited.vertices().stream().filter(v -> edited.jumpInfo(v) != null).findFirst().orElseThrow();
            expect(edited.jumpInfo(jump).variable().equals("n") && edited.jumpInfo(jump).targetLabel().equals("alias")
                            && edited.isForwardJump(jump), "new explicit jump reaches the chosen forward target");
            expect(run(flow, Map.of()).get("out") == 2 && run(edited, Map.of()).get("out") == 1,
                    "moving the return changes execution, not merely the drawing");
            compareMachine(edited, Map.of());
            Flowchart.Vertex firstBody = vertexFor(flow, "DEC n;");
            String bodyTarget = flow.retargetJump(footer.owner(), firstBody.owner());
            Flowchart bodyFlow = graph(bodyTarget, LanguageVersion.STRUCTURED);
            expect(bodyTarget.contains("alias: DEC n;") && bodyFlow.document().main().nodes().get(1).labels().isEmpty(),
                    "the loop's explicit entry label moves to the body destination");
            expect(run(bodyFlow, Map.of()).equals(run(flow, Map.of())), "jumping to the first body statement preserves this loop's behavior");
            compareMachine(bodyFlow, Map.of());
        });

        check("loop conversion preserves comments CRLF array operands and all body bytes", () -> {
            String source = "var cells[1]=2;\r\nbegin:\r\nentry: // entry label\r\ndo // before body\r\n"
                    + "{ // body comment\r\n  DEC cells[0];\r\n}\r\n// before test\r\nwhile // while comment\r\n(\r\n"
                    + " cells // operand comment\r\n [00] // before operator\r\n != // before zero\r\n 00 // after zero\r\n);\r\nfinish: HALT;\r\n";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            StructuredDiagram.Node loop = flow.document().main().nodes().get(0);
            String changed = flow.retargetJump(loop, flow.document().main().nodes().get(1));
            String expected = source.replace("entry:", "").replace("finish:", "entry: finish:").replace("do // before body", " // before body")
                    .replace("while // while comment", "JNZ // while comment").replace("(\r\n", " \r\n")
                    .replace("!= // before zero", "  // before zero").replace(" 00 // after zero", " entry // after zero")
                    .replace(");\r\n", ";\r\n");
            expect(changed.equals(expected), "only control tokens are translated; every comment and all surrounding trivia stay in place");
            expect(changed.contains(source.substring(loop.body().start(), loop.body().end())), "body bytes are retained verbatim");
            DiagramValidation.Result checked = DiagramValidation.validate(changed, ALL, Map.of(), LanguageVersion.STRUCTURED, true);
            expect(checked.valid(), checked.message());
            Flowchart edited = Flowchart.from(checked.diagram());
            Flowchart.JumpInfo jump = edited.vertices().stream().map(edited::jumpInfo).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            expect(jump.variable().equals("cells[00]") && jump.operand().text(changed).contains("// operand comment"),
                    "indexed operand spelling and its internal comment survive conversion");
        });

        check("nested and empty loop returns keep distinct source entries without invented processes", () -> {
            String source = "var n=2; begin: outer: do { inner: do { DEC n; } while(n!=0); } while(n!=0); HALT;";
            Flowchart flow = graph(source, LanguageVersion.STRUCTURED);
            StructuredDiagram.Node outer = flow.document().main().nodes().get(0), inner = outer.body().nodes().get(0);
            expect(flow.entryVertex(outer) != flow.entryVertex(inner) && flow.labels().get("outer") == flow.entryVertex(outer)
                            && flow.labels().get("inner") == flow.entryVertex(inner), "nested labels retain their own source-backed entries");
            String moved = flow.retargetJump(inner, outer);
            Flowchart edited = graph(moved, LanguageVersion.STRUCTURED);
            expect(edited.document().main().nodes().get(0).kind() == StructuredDiagram.Kind.DO_WHILE
                            && edited.document().main().nodes().get(0).body().nodes().get(0).kind() == StructuredDiagram.Kind.BLOCK,
                    "only the dragged inner loop changes notation");
            compareMachine(edited, Map.of());
            Flowchart empty = graph("var n=0; begin: empty: do {} while(n!=0); HALT;", LanguageVersion.STRUCTURED);
            Flowchart.Vertex footer = empty.vertices().stream().filter(v -> empty.loopJumpInfo(v) != null).findFirst().orElseThrow();
            expect(empty.vertices().stream().noneMatch(v -> v.kind() == Flowchart.Kind.PROCESS), "empty loop introduces no placeholder instruction");
            expect(edge(empty, empty.jumpTarget(footer).id(), "").to() == footer.id(), "empty body entry flows straight into the footer");
            Flowchart changed = graph(empty.retargetJump(footer.id(), vertexFor(empty, "HALT;").id()), LanguageVersion.STRUCTURED);
            compareMachine(changed, Map.of("n", 0));
            compareMachine(changed, Map.of("n", 2));
        });

        check("equality and general post-tests retain their actual condition and cannot silently become JNZ", () -> {
            String source = "var n=0; var rounds=0; begin: do { INC n; INC rounds; } while(n==0); HALT;";
            DiagramValidation.Result checked = DiagramValidation.validate(source, Set.of(GameFeature.DO_WHILE), Map.of(), LanguageVersion.STRUCTURED, true);
            expect(checked.valid(), "zero equality is already available in the first world: " + checked.message());
            Flowchart flow = Flowchart.from(checked.diagram());
            Flowchart.Vertex footer = flow.vertices().stream().filter(v -> flow.loopJumpInfo(v) != null).findFirst().orElseThrow();
            expect(!flow.comparisonInfo(footer).canonicalNotEqual() && !flow.canRetargetJump(footer.id())
                            && !flow.loopJumpInfo(footer).retargetable(), "equality remains equality and is not a direct nonzero jump");
            expect(flow.isJumpRoute(edge(flow, footer.id(), "true")) && !flow.isForwardJump(footer),
                    "its true repeat edge still has the backward return route");
            expect(flow.retargetJump(footer.id(), flow.jumpTarget(footer).id()).equals(source), "same-entry drop cannot rewrite even an equality loop");
            try { flow.retargetJump(footer.id(), vertexFor(flow, "HALT;").id()); throw new AssertionError("equality converted to nonzero jump"); }
            catch (IllegalArgumentException expected) { expect(expected.getMessage().contains("variable != 0"), "conversion limitation is explicit"); }
            compareMachine(flow, Map.of("n", 0));
            compareMachine(flow, Map.of("n", 2));
            Flowchart general = graph("var n=0; begin: do { INC n; } while(n!=3); HALT;", LanguageVersion.STRUCTURED);
            Flowchart.Vertex comparison = general.vertices().stream().filter(v -> general.loopJumpInfo(v) != null).findFirst().orElseThrow();
            expect(!general.canRetargetJump(comparison.id()) && general.comparisonInfo(comparison).rightText().equals("3"),
                    "nonzero literal comparisons preserve their source predicate too");
            compareMachine(general, Map.of());
        });

        check("unresolved and malformed jumps fail clearly without inventing destinations", () -> {
            String source = "var n=1; begin: JNZ n missing; HALT;";
            DiagramValidation.Result invalid = DiagramValidation.validate(source, ALL, Map.of(), LanguageVersion.STRUCTURED, true);
            expect(!invalid.valid() && invalid.message().equals("Unresolved labels: missing"), "real compiler rejects missing label");
            try { Flowchart.from(invalid.diagram()); throw new AssertionError("unresolved flowchart was accepted"); }
            catch (IllegalArgumentException expected) { expect(expected.getMessage().contains("missing"), "graph diagnostic names target"); }
            rejected(() -> StructuredDiagram.parse("var n=1; begin: JNZ n; HALT;", LanguageVersion.STRUCTURED, true), "missing JNZ operand");
            DiagramValidation.Result wrong = DiagramValidation.validate("var n=1; begin: JNZ absent target; target: HALT;", ALL, Map.of(), LanguageVersion.STRUCTURED, true);
            expect(!wrong.valid() && wrong.message().contains("Unknown variable absent"), "jump variable is validated");
        });

        check("flow graph limits and every source owner are bounded and consistent", () -> {
            Flowchart flow = graph("begin: NOP; HALT;", LanguageVersion.BEFORE_DO_WHILE);
            expect(flow.vertices().get(0).kind() == Flowchart.Kind.START, "START first");
            for (Flowchart.Vertex vertex : flow.vertices()) {
                expect(flow.vertex(vertex.id()) == vertex, "stable vertex identifiers");
                if (vertex.owner() != null) expect(vertex.start() == vertex.owner().start() && vertex.end() == vertex.owner().end(), "owner span");
            }
            for (Flowchart.Edge edge : flow.edges()) expect(edge.from() >= 0 && edge.to() >= 0
                    && edge.from() < flow.vertices().size() && edge.to() < flow.vertices().size(), "all edges resolve");
            try {
                Flowchart.from(StructuredDiagram.parse("begin:" + "NOP;".repeat(1001), LanguageVersion.BEFORE_DO_WHILE, true));
                throw new AssertionError("oversized graph accepted");
            } catch (IllegalArgumentException expected) { }
        });

        check("all eight foundations starters and reference solutions match machine control flow", () -> {
            Set<GameFeature> earned = EnumSet.noneOf(GameFeature.class);
            Map<String, String> solutions = GameCurriculumTest.solutions();
            int levels = 0, executions = 0;
            for (GameLevel level : GameCatalog.levels()) {
                if (GameWorld.forLevel(level) != GameWorld.MACHINE_FOUNDATIONS) break;
                for (String source : List.of(level.starter(), solutions.get(level.id()))) {
                    LanguageVersion version = GameLanguage.editingVersionFor(level, earned, source);
                    DiagramValidation.Result validated = DiagramValidation.validate(source, earned, Map.of(), version, true);
                    expect(validated.valid(), level.id() + ": " + validated.message());
                    Flowchart flow = Flowchart.from(validated.diagram());
                    for (GameLevel.TestCase test : level.cases()) {
                        compareMachine(flow, test.inputs());
                        if (source.equals(solutions.get(level.id()))) {
                            Map<String, Integer> actual = run(flow, test.inputs());
                            for (var expected : test.expected().entrySet()) {
                                expect(actual.get(expected.getKey()).equals(expected.getValue()), "reference graph output " + level.id());
                            }
                        }
                        ++executions;
                    }
                }
                earned.addAll(level.unlocks());
                ++levels;
            }
            expect(levels == 8 && executions > 50, "complete first-world fixture coverage");
        });

        System.out.println("FlowchartTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " flowchart checks failed");
    }

    private static Flowchart graph(String source, LanguageVersion version) throws Exception {
        return Flowchart.from(StructuredDiagram.parse(source, version, true));
    }

    private static Flowchart.Vertex vertexFor(Flowchart flow, String text) {
        return flow.vertices().stream().filter(v -> v.owner() != null && v.owner().editText().equals(text)).findFirst().orElseThrow();
    }

    private static List<Flowchart.Edge> outgoing(Flowchart flow, int id) { return flow.edges().stream().filter(edge -> edge.from() == id).toList(); }
    private static Flowchart.Edge edge(Flowchart flow, int from, String label) {
        return outgoing(flow, from).stream().filter(edge -> edge.label().equals(label)).findFirst().orElseThrow();
    }

    /** A small independent graph interpreter for the machine commands taught in world one. */
    private static Map<String, Integer> run(Flowchart flow, Map<String, Integer> inputs) {
        Map<String, Integer> values = initial(flow.document());
        values.putAll(inputs);
        int current = 0;
        for (int steps = 0; steps < 5000; steps++) {
            Flowchart.Vertex vertex = flow.vertex(current);
            if (vertex.kind() == Flowchart.Kind.HALT || vertex.kind() == Flowchart.Kind.END) return values;
            String branch = "";
            if (vertex.kind() == Flowchart.Kind.DECISION) {
                Matcher condition = Pattern.compile("([A-Za-z_][A-Za-z_0-9]*|[0-9]+)\\s*(==|!=)\\s*([0-9]+)").matcher(vertex.text().strip());
                expect(condition.matches(), "test interpreter condition " + vertex.text());
                int left = Character.isDigit(condition.group(1).charAt(0)) ? Integer.parseInt(condition.group(1)) : values.get(condition.group(1));
                boolean equal = left == Integer.parseInt(condition.group(3));
                branch = Boolean.toString(condition.group(2).equals("==") ? equal : !equal);
            } else if (vertex.kind() == Flowchart.Kind.PROCESS) {
                String text = vertex.owner().editText().replaceAll("//[^\\r\\n]*", "").strip();
                if (!text.equals(";") && !text.equals("NOP;")) {
                    Matcher command = Pattern.compile("(INC|DEC)\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*;").matcher(text);
                    expect(command.matches(), "test interpreter process " + text);
                    String name = command.group(2);
                    values.put(name, command.group(1).equals("INC") ? values.get(name) + 1 : Math.max(0, values.get(name) - 1));
                }
            }
            current = edge(flow, current, branch).to();
        }
        throw new AssertionError("graph did not terminate within the test budget");
    }

    private static Map<String, Integer> initial(StructuredDiagram document) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (StructuredDiagram.Node declaration : document.preamble().nodes()) {
            Matcher matcher = Pattern.compile("var\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*=\\s*([0-9]+)\\s*;").matcher(declaration.editText());
            expect(matcher.matches(), "world-one globals are scalar numeric literals");
            values.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return values;
    }

    private static void compareMachine(Flowchart flow, Map<String, Integer> inputs) throws Exception {
        CompilationResult compiled = Compiler.compileWithSourceMap(new SourceDocument(Path.of("flow.a26"), flow.document().source()),
                path -> { throw new AssertionError("unexpected import"); }, null, flow.document().languageVersion());
        Machine machine = new Machine();
        machine.load(compiled.bytecode());
        List<String> variables = new ArrayList<>(initial(flow.document()).keySet());
        for (var input : inputs.entrySet()) machine.set(variables.indexOf(input.getKey()) + 1, input.getValue());
        machine.step(20_000);
        expect(machine.isHalted(), "machine reference terminates");
        Map<String, Integer> graph = run(flow, inputs);
        for (int i = 0; i < variables.size(); i++) expect(graph.get(variables.get(i)) == machine.get(i + 1), "graph/machine mismatch for " + variables.get(i));
    }

    private static void rejected(Check action, String message) throws Exception {
        try { action.run(); } catch (StructuredDiagram.ParseException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(String name, Check action) {
        try { action.run(); ++passed; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { ++failed; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
