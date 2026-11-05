package machine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Syntax and resource checks for the game's earned subset of the source language. */
final class GameSyntax {
    static final Path MAIN = Path.of("solution.a26");
    static final int MAX_CODE = 200_000;
    private static final int MAX_SOURCE = 65_536;
    private static final int MAX_COMBINED_SOURCE = 131_072;
    private static final int MAX_TOKENS = 12_000;
    private static final int MAX_ARRAY = 256;
    private static final int MAX_DATA = 2048;
    private static final int MAX_LOCAL_LITERAL = 4096;
    private static final int MAX_ARITY = 32;
    private static final Set<String> RESERVED = Set.of("var", "void", "if", "else", "while",
        "HALT", "NOP", "INC", "DEC", "JNZ", "begin:");

    record Lexeme(String text, int start, int end, int line, int column, Path file, char kind) {
        SourceLocation location() { return new SourceLocation(file, start, end, line, column); }
    }

    record Value(Lexeme token, Integer index) {
        boolean literal() { return token.kind == 'n'; }
    }

    record Declaration(String name, int length, boolean array, Value initializer, int address, Lexeme token) {}
    record Analysis(String source, Map<String, Declaration> globals, Map<String, String> libraries,
            Set<GameFeature> usedFeatures, LanguageVersion version) {}

    static final class Failure extends Exception {
        final SourceLocation location;
        Failure(String message, Lexeme token) {
            super(message);
            location = token == null ? null : token.location();
        }
    }

    private static final class Scope {
        final Map<String, Declaration> locals = new LinkedHashMap<>();
        final Set<String> parameters = new HashSet<>();
        final boolean macro;
        Scope(boolean macro) { this.macro = macro; }
    }

    private record Reference(Value value, Scope scope) {}
    private record Signature(String name, int arity) {}
    private record Call(Signature signature, List<Value> arguments, int nesting, Lexeme token) {}
    private record Invocation(Signature signature, List<Integer> literals) {}
    private record Initialization(int length, Value initializer) {}
    private static final class Body {
        long size;
        int nesting;
        final List<Lexeme> parameters = new ArrayList<>();
        final List<Call> calls = new ArrayList<>();
        final List<Initialization> initializers = new ArrayList<>();
    }

    private final Set<GameFeature> available;
    private final Map<String, String> toolbox;
    private final BooleanSupplier cancelled;
    private final LanguageVersion version;
    private final boolean explicitJumps;
    private final Map<String, Declaration> globals = new LinkedHashMap<>();
    private final Map<String, String> imports = new LinkedHashMap<>();
    private final Map<Signature, List<Body>> macros = new LinkedHashMap<>();
    private final Set<Signature> mainMacros = new HashSet<>();
    private final List<Reference> references = new ArrayList<>();
    private final Set<GameFeature> usedFeatures = new HashSet<>();
    private int sourceSize;
    private int tokenCount;
    private int declarations;
    private int globalAddress = 1;
    private int macroCount;

    private GameSyntax(Set<GameFeature> available, Map<String, String> libraries, BooleanSupplier cancelled,
            LanguageVersion version, boolean explicitJumps) {
        this.available = available;
        this.toolbox = libraries;
        this.cancelled = cancelled;
        this.version = version;
        this.explicitJumps = explicitJumps;
    }

    static Analysis inspect(String source, Set<GameFeature> available, Map<String, String> libraries,
            BooleanSupplier cancelled, String exerciseId, GameBookLevels.MacroRequirement requiredMacro,
            LanguageVersion version, boolean explicitJumps) throws Failure {
        GameSyntax syntax = new GameSyntax(available, libraries, cancelled, version, explicitJumps);
        if (source.length() > MAX_SOURCE) throw new Failure("Source text exceeds the game's resource limit.", null);
        String normalized = new SourceDocument(MAIN, source).text();
        Parser parser = syntax.new Parser(normalized, MAIN);
        Body body = parser.program(exerciseId);
        if (requiredMacro != null) {
            Signature signature = new Signature(requiredMacro.name(), requiredMacro.arity());
            String description = requiredMacro.name() + " with " + requiredMacro.arity() + " parameters";
            if (!syntax.mainMacros.contains(signature)) {
                throw new Failure("Define " + description + " in your own source for this level.", null);
            }
            if (body.calls.stream().noneMatch(call -> call.signature.equals(signature))) {
                throw new Failure("Call " + description + " from the main program for this level.", null);
            }
        }
        syntax.validateReferences();
        Set<Signature> validated = new HashSet<>();
        for (Signature signature : syntax.macros.keySet()) {
            syntax.validateMacroCycles(signature, validated, new HashSet<>());
        }
        syntax.bodyCost(body, Map.of(), new HashMap<>());
        syntax.bodyDepth(body, new HashMap<>());
        return new Analysis(normalized, Map.copyOf(syntax.globals), Map.copyOf(syntax.imports), Set.copyOf(syntax.usedFeatures), version);
    }

    /** Only the conversion lesson may debug its previous source form. Comments and paths do not count. */
    static boolean usesLegacyDo(String source, BooleanSupplier cancelled) {
        try {
            GameSyntax syntax = new GameSyntax(Set.of(), Map.of(), cancelled, LanguageVersion.BEFORE_DO_WHILE, true);
            List<Lexeme> tokens = syntax.tokenize(source, MAIN);
            boolean identifier = false;
            for (int index = 0; index + 1 < tokens.size(); index++) {
                if (tokens.get(index).kind == 'i' && tokens.get(index).text.equals("do")) {
                    if (tokens.get(index + 1).text.equals("{")) return false;
                    identifier = true;
                }
            }
            return identifier;
        } catch (Failure failure) {
            return false; // Retain the original syntax/resource diagnostic.
        }
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    private void require(GameFeature feature, Lexeme token) throws Failure {
        requireAvailable(feature, token);
        if (MAIN.equals(token.file)) usedFeatures.add(feature);
    }

    private void requireAvailable(GameFeature feature, Lexeme token) throws Failure {
        if (!available.contains(feature)) {
            throw new Failure(feature.title() + " is still locked for this submission.", token);
        }
    }

    private int number(Lexeme token) throws Failure {
        try {
            if (token.text.length() > 10) throw new NumberFormatException();
            return Integer.parseInt(token.text);
        } catch (NumberFormatException exception) {
            throw new Failure("Use a nonnegative integer no larger than " + Integer.MAX_VALUE + ".", token);
        }
    }

    private void validateReferences() throws Failure {
        for (Reference reference : references) {
            checkCancelled();
            Value value = reference.value;
            if (reference.scope.parameters.contains(value.token.text)) continue;
            Declaration declaration = reference.scope.locals.get(value.token.text);
            if (declaration == null) declaration = globals.get(value.token.text);
            if (declaration == null) throw new Failure("Unknown variable " + value.token.text + ".", value.token);
            if (value.index != null && (!declaration.array || value.index >= declaration.length)) {
                throw new Failure("Array index is outside " + declaration.name + ".", value.token);
            }
        }
    }

    private void validateMacroCycles(Signature signature, Set<Signature> validated, Set<Signature> visiting) throws Failure {
        checkCancelled();
        if (validated.contains(signature) || !macros.containsKey(signature)) return;
        if (!visiting.add(signature)) throw new Failure("Recursive macro expansion exceeds the game's resource limits.", null);
        for (Body body : macros.get(signature)) {
            for (Call call : body.calls) validateMacroCycles(call.signature, validated, visiting);
        }
        visiting.remove(signature);
        validated.add(signature);
    }

    private long macroCost(Invocation invocation, Map<Invocation, Long> memo) throws Failure {
        checkCancelled();
        if (memo.containsKey(invocation)) return memo.get(invocation);
        long maximum = 0;
        // Literal specializations of the same name and arity share a conservative upper bound.
        for (Body body : macros.getOrDefault(invocation.signature, List.of())) {
            Map<String, Integer> parameters = new HashMap<>();
            for (int index = 0; index < body.parameters.size(); index++) {
                Lexeme parameter = body.parameters.get(index);
                if (parameter.kind != 'n') parameters.put(parameter.text, invocation.literals.get(index));
            }
            maximum = Math.max(maximum, bodyCost(body, parameters, memo));
        }
        memo.put(invocation, maximum);
        return maximum;
    }

    /** A variable copy has fixed-size code; only literal initializers grow with the value. */
    private int literalValue(Value value, Map<String, Integer> parameters) throws Failure {
        return value.literal() ? number(value.token) : parameters.getOrDefault(value.token.text, -1);
    }

    private long bodyCost(Body body, Map<String, Integer> parameters, Map<Invocation, Long> memo) throws Failure {
        long cost = body.size;
        for (Initialization initialization : body.initializers) {
            int literal = literalValue(initialization.initializer, parameters);
            cost += (long) initialization.length * Math.max(32, 2L * literal + 8);
        }
        for (Call call : body.calls) {
            List<Integer> literals = new ArrayList<>();
            for (Value argument : call.arguments) {
                int literal = literalValue(argument, parameters);
                literals.add(literal);
                // A literal may become a writable temporary, including after forwarding.
                if (literal >= 0) cost += Math.max(32, 2L * literal + 8);
            }
            cost += macroCost(new Invocation(call.signature, List.copyOf(literals)), memo);
            if (cost > MAX_CODE) throw new Failure("Macro expansion exceeds the game's resource limit.", call.token);
        }
        if (cost > MAX_CODE) throw new Failure("This program would generate too much code for a game check.", null);
        return cost;
    }

    private int bodyDepth(Body body, Map<Signature, Integer> memo) throws Failure {
        int maximum = body.nesting;
        for (Call call : body.calls) {
            checkCancelled();
            if (!memo.containsKey(call.signature)) {
                int depth = 0;
                for (Body definition : macros.getOrDefault(call.signature, List.of())) {
                    depth = Math.max(depth, bodyDepth(definition, memo));
                }
                memo.put(call.signature, depth);
            }
            maximum = Math.max(maximum, call.nesting + memo.get(call.signature));
            if (maximum > 64) throw new Failure("Expanded macro blocks are nested too deeply for a game check.", call.token);
        }
        return maximum;
    }

    private List<Lexeme> tokenize(String source, Path file) throws Failure {
        checkCancelled();
        sourceSize += source.length();
        if (source.length() > MAX_SOURCE || sourceSize > MAX_COMBINED_SOURCE) {
            throw new Failure("Source text exceeds the game's resource limit.", null);
        }
        List<Lexeme> tokens = new ArrayList<>();
        int position = 0, line = 1, column = 1;
        while (position < source.length()) {
            if ((position & 255) == 0) checkCancelled();
            char character = source.charAt(position);
            if (character == ' ' || character == '\t' || character == '\r' || character == '\n') {
                if (character == '\n') { ++line; column = 1; } else { ++column; }
                ++position;
                continue;
            }
            if (character == '/' && position + 1 < source.length() && source.charAt(position + 1) == '/') {
                while (position < source.length() && source.charAt(position) != '\n') { ++position; ++column; }
                continue;
            }
            int start = position, startColumn = column;
            char kind = 'p';
            if (letter(character)) {
                kind = 'i';
                while (position < source.length() && (letter(source.charAt(position)) || digit(source.charAt(position)))) ++position;
                if (position - start == 5 && source.startsWith("begin:", start)) { ++position; kind = 'p'; }
                if (position - start > 128) throw new Failure("Identifier exceeds the game's resource limit.", null);
            } else if (digit(character)) {
                kind = 'n';
                while (position < source.length() && digit(source.charAt(position))) ++position;
            } else if (character == '"') {
                kind = 's';
                ++position;
                while (position < source.length() && source.charAt(position) != '"' && source.charAt(position) != '\n') ++position;
                if (position == source.length() || source.charAt(position) != '"') {
                    throw new Failure("Close the quoted import path on the same line.", new Lexeme("", start, position, line, startColumn, file, kind));
                }
                ++position;
            } else if ("{}[](),;:=".indexOf(character) >= 0 || character == '!') {
                ++position;
                if ((character == '=' || character == '!') && position < source.length() && source.charAt(position) == '=') ++position;
                else if (character == '!') throw new Failure("Use != for a comparison.", new Lexeme("!", start, position, line, startColumn, file, kind));
            } else {
                throw new Failure("Unsupported character: " + character, new Lexeme(String.valueOf(character), start, start + 1, line, column, file, kind));
            }
            Lexeme token = new Lexeme(source.substring(start, position), start, position, line, startColumn, file, kind);
            tokens.add(token);
            if (kind == 'n') number(token);
            column += position - start;
            if (++tokenCount > MAX_TOKENS) throw new Failure("Too many tokens for a game check.", token);
        }
        tokens.add(new Lexeme("<end>", source.length(), source.length(), line, column, file, 'e'));
        return tokens;
    }

    private static boolean letter(char character) {
        return character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' || character == '_';
    }
    private static boolean digit(char character) { return character >= '0' && character <= '9'; }

    private final class Parser {
        final String source;
        final Path file;
        final List<Lexeme> tokens;
        int position;
        int nesting;

        Parser(String source, Path file) throws Failure {
            this.source = source;
            this.file = file;
            tokens = tokenize(source, file);
        }

        Lexeme peek() { return tokens.get(position); }
        boolean at(String text) { return peek().text.equals(text); }
        Lexeme take() { checkCancelled(); return tokens.get(position++); }
        boolean accept(String text) { if (!at(text)) return false; take(); return true; }
        Lexeme need(String text) throws Failure {
            if (!at(text)) throw new Failure("Expected " + text + ", found " + peek().text + ".", peek());
            return take();
        }
        Lexeme identifier() throws Failure {
            if (at("do") && version == LanguageVersion.STRUCTURED) {
                throw new Failure("do is a keyword in the structured language version; choose an ordinary identifier.", peek());
            }
            if (peek().kind != 'i' || RESERVED.contains(peek().text)) {
                throw new Failure("Expected a variable, label, or macro name.", peek());
            }
            return take();
        }
        Lexeme numeric() throws Failure {
            if (peek().kind != 'n') throw new Failure("Expected a numeric literal.", peek());
            return take();
        }

        Body program(String exerciseId) throws Failure {
            while (at("import") || at("void")) {
                if (at("import")) imported(); else macro();
            }
            while (at("var")) declaration(null, null);
            need("begin:");
            Body body = new Body();
            Scope scope = new Scope(false);
            int start = position;
            while (!at("<end>")) statement(scope, body);
            if ("clear-block".equals(exerciseId)) checkClearBlock(start);
            if ("clear-do-while".equals(exerciseId)) checkClearDoWhile(start);
            if ("while-practice".equals(exerciseId) && usedFeatures.contains(GameFeature.WHILE)) {
                checkWhileRewrite(start);
            }
            if ("else-practice".equals(exerciseId) && usedFeatures.contains(GameFeature.ELSE)) {
                checkConditionalRewrite(start, 0, true);
            }
            if ("comparison-practice".equals(exerciseId) && usedFeatures.contains(GameFeature.COMPARISONS)) {
                checkConditionalRewrite(start, 3, false);
            }
            body.size += 8L * (position - start);
            return body;
        }

        /** The introductory exercise groups the loop and adds an empty setup placeholder. */
        void checkClearBlock(int start) throws Failure {
            List<String> expected = List.of("INC", "dummy", ";", ";", "do", ":", "{", "DEC", "x", ";",
                    "}", "JNZ", "x", "do", ";", "HALT", ";", "<end>");
            checkTokens(start, expected, "Group the clearing loop as do: { DEC x; } JNZ x do;, keeping the jump outside "
                    + "the block. Add a standalone ; placeholder immediately after INC dummy; and before the block. "
                    + "Keep HALT; after the jump and preserve the instruction order.");
        }

        void checkClearDoWhile(int start) throws Failure {
            List<String> expected = List.of("INC", "dummy", ";", ";", "do", "{", "DEC", "x", ";", "}",
                    "while", "(", "x", "!=", "0", ")", ";", "HALT", ";", "<end>");
            checkTokens(start, expected, "Rewrite the clearing loop as do { DEC x; } while (x != 0);, "
                    + "keeping the standalone ; placeholder immediately after INC dummy; before the loop, and HALT; after it. "
                    + "Replace the label and jump rather than adding another loop.");
        }

        void checkWhileRewrite(int start) throws Failure {
            List<String> expected = List.of("while", "(", "n", "!=", "0", ")", "{",
                    "DEC", "n", ";", "INC", "count", ";", "}", "HALT", ";", "<end>");
            checkTokens(start, expected, "Replace the outer if and do/while with while (n != 0) { DEC n; INC count; }, "
                    + "followed directly by HALT;. Keep DEC n before INC count inside the loop, "
                    + "with no other main-program statements. Rewrite the original loop rather than adding another one.");
        }

        void checkTokens(int start, List<String> expected, String message) throws Failure {
            for (int index = 0; index < expected.size(); index++) {
                Lexeme token = tokens.get(Math.min(start + index, tokens.size() - 1));
                if (!expected.get(index).equals(token.text)) {
                    throw new Failure(message, token);
                }
            }
        }

        /** Require the taught rewrite, while leaving branch algorithms to the output checks. */
        void checkConditionalRewrite(int start, int literal, boolean requireElse) throws Failure {
            String message = "After begin:, use one if comparing n == " + literal + " or n != " + literal
                    + (requireElse ? ", with an else branch" : ", optionally with an else branch")
                    + ", followed directly by HALT;. Put all work inside the branches, with no other top-level statements.";
            int cursor = rewriteToken(start, "if", message);
            cursor = rewriteToken(cursor, "(", message);
            cursor = rewriteToken(cursor, "n", message);
            Lexeme operator = tokens.get(cursor++);
            if (!operator.text.equals("==") && !operator.text.equals("!=")) throw new Failure(message, operator);
            Lexeme right = tokens.get(cursor++);
            if (right.kind != 'n' || number(right) != literal) throw new Failure(message, right);
            cursor = rewriteToken(cursor, ")", message);
            cursor = rewriteBlock(cursor, message);
            if (tokens.get(cursor).text.equals("else")) {
                cursor = rewriteBlock(cursor + 1, message);
            } else if (requireElse) {
                throw new Failure(message, tokens.get(cursor));
            }
            cursor = rewriteToken(cursor, "HALT", message);
            cursor = rewriteToken(cursor, ";", message);
            rewriteToken(cursor, "<end>", message);
        }

        int rewriteToken(int index, String expected, String message) throws Failure {
            Lexeme token = tokens.get(index);
            if (!expected.equals(token.text)) throw new Failure(message, token);
            return index + 1;
        }

        int rewriteBlock(int start, String message) throws Failure {
            int cursor = rewriteToken(start, "{", message);
            int depth = 1;
            // The ordinary parser has already validated all blocks and their nesting limit.
            while (depth != 0) {
                Lexeme token = tokens.get(cursor++);
                if (token.text.equals("{")) depth++;
                if (token.text.equals("}")) depth--;
            }
            return cursor;
        }

        void imported() throws Failure {
            Lexeme token = need("import");
            require(GameFeature.IMPORTS, token);
            Lexeme path = peek();
            if (path.kind != 's') throw new Failure("Import needs a quoted toolbox filename.", path);
            take();
            Lexeme semicolon = need(";");
            int lineStart = source.lastIndexOf('\n', token.start - 1) + 1;
            int lineEnd = source.indexOf('\n', semicolon.end);
            if (lineEnd < 0) lineEnd = source.length();
            if (!source.substring(lineStart, token.start).isBlank() || !source.substring(semicolon.end, lineEnd).isBlank()
                    || path.end != semicolon.start) {
                throw new Failure("Put each import on its own line, ending with \"; and no trailing comment.", token);
            }
            String name = path.text.substring(1, path.text.length() - 1).trim();
            String library = toolbox.get(name);
            if (library == null || name.isEmpty()) throw new Failure("Toolbox library is not available: " + name, path);
            if (library.length() > MAX_SOURCE) throw new Failure("Toolbox source exceeds the game's resource limit.", path);
            String normalized = new SourceDocument(Path.of("toolbox", name), library).text();
            imports.put(name, normalized);
            Parser parser = new Parser(normalized, Path.of("toolbox", name));
            while (parser.at("void")) parser.macro();
            if (!parser.at("<end>")) throw new Failure("Toolbox libraries may contain macro definitions only; nested imports are not supported.", parser.peek());
        }

        void macro() throws Failure {
            Lexeme token = need("void");
            require(GameFeature.MACROS, token);
            if (++macroCount > 64) throw new Failure("Too many macro definitions for a game check.", token);
            Lexeme name = identifier();
            Scope scope = new Scope(true);
            need("(");
            int arity = 0;
            List<Lexeme> parameters = new ArrayList<>();
            if (!at(")")) {
                do {
                    if (++arity > MAX_ARITY) throw new Failure("Too many macro parameters for a game check (maximum " + MAX_ARITY + ").", peek());
                    if (peek().kind == 'n') {
                        require(GameFeature.LITERAL_PARAMETERS, peek());
                        parameters.add(take());
                    } else {
                        Lexeme parameter = identifier();
                        if (!scope.parameters.add(parameter.text)) throw new Failure("Macro parameter names must be unique.", parameter);
                        parameters.add(parameter);
                    }
                } while (accept(","));
            }
            need(")");
            Body body = new Body();
            body.parameters.addAll(parameters);
            int start = position;
            block(scope, body);
            body.size += 8L * (position - start);
            macros.computeIfAbsent(new Signature(name.text, arity), ignored -> new ArrayList<>()).add(body);
            if (MAIN.equals(file)) mainMacros.add(new Signature(name.text, arity));
        }

        void declaration(Scope scope, Body body) throws Failure {
            Lexeme token = need("var");
            if (scope != null) require(GameFeature.LOCAL_VARIABLES, token);
            Lexeme name = identifier();
            boolean array = accept("[");
            int length = 1;
            if (array) {
                require(GameFeature.ARRAYS, name);
                Lexeme size = numeric();
                length = number(size);
                if (length < 1 || length > MAX_ARRAY) throw new Failure("Game arrays need 1 to " + MAX_ARRAY + " cells.", size);
                need("]");
            }
            need("=");
            Value initial = value(scope, false);
            if (!initial.literal()) require(GameFeature.COPYING, initial.token);
            need(";");
            declarations += length;
            if (declarations > MAX_DATA) throw new Failure("Too many declared cells for a game check.", name);
            Declaration declaration = new Declaration(name.text, length, array, initial, scope == null ? globalAddress : -1, name);
            if (scope == null) {
                if (globals.containsKey(name.text)) throw new Failure("Global variable " + name.text + " is declared more than once.", name);
                if (!initial.literal()) {
                    Declaration previous = globals.get(initial.token.text);
                    if (previous == null) throw new Failure("Global initializers may copy only earlier globals.", initial.token);
                    if (initial.index != null && (!previous.array || initial.index >= previous.length)) {
                        throw new Failure("Array initializer index is out of bounds.", initial.token);
                    }
                }
                globals.put(name.text, declaration);
                globalAddress += length;
            } else {
                if (scope.locals.containsKey(name.text) || scope.parameters.contains(name.text)
                        || !scope.macro && globals.containsKey(name.text)) {
                    throw new Failure("Local variable " + name.text + " redeclares an existing variable or test port.", name);
                }
                scope.locals.put(name.text, declaration);
                Integer literal = initial.literal() ? number(initial.token) : null;
                if (literal != null && literal > MAX_LOCAL_LITERAL) {
                    throw new Failure("Runtime initializers are limited to " + MAX_LOCAL_LITERAL + " in game checks.", initial.token);
                }
                body.initializers.add(new Initialization(length, initial));
            }
        }

        Value value(Scope scope, boolean variableOnly) throws Failure {
            if (!variableOnly && peek().kind == 'n') return new Value(take(), null);
            Lexeme name = identifier();
            Integer index = null;
            if (accept("[")) {
                require(GameFeature.ARRAYS, name);
                index = number(numeric());
                need("]");
            }
            Value value = new Value(name, index);
            if (scope != null) references.add(new Reference(value, scope));
            return value;
        }

        void condition(Scope scope, Body body) throws Failure {
            need("(");
            Value left = value(scope, false);
            if (!at("==") && !at("!=")) throw new Failure("Conditions use == or !=.", peek());
            Lexeme operator = take();
            Value right = value(scope, false);
            if (left.literal() || !right.literal() || number(right.token) != 0) require(GameFeature.COMPARISONS, operator);
            need(")");
            body.size += 128;
        }

        void block(Scope scope, Body body) throws Failure {
            Lexeme token = need("{");
            if (++nesting > 64) throw new Failure("Blocks are nested too deeply for a game check.", token);
            body.nesting = Math.max(body.nesting, nesting);
            while (!at("}") && !at("<end>")) statement(scope, body);
            need("}");
            --nesting;
        }

        void statement(Scope scope, Body body) throws Failure {
            while (peek().kind == 'i' && position + 1 < tokens.size() && tokens.get(position + 1).text.equals(":")) {
                identifier(); need(":");
            }
            Lexeme token = peek();
            if (at("{")) { require(GameFeature.BLOCKS, token); block(scope, body); }
            else if (at("var")) declaration(scope, body);
            else if (accept("HALT") || accept("NOP")) need(";");
            else if (accept("INC") || accept("DEC")) { value(scope, true); need(";"); }
            else if (accept("JNZ")) {
                if (!explicitJumps) {
                    throw new Failure("Explicit JNZ is suspended in this world. Use if/else, while, or do/while; the compiler still generates jumps internally.", token);
                }
                value(scope, true); identifier(); need(";");
            }
            else if (accept("while")) { require(GameFeature.WHILE, token); condition(scope, body); block(scope, body); }
            else if (accept("if")) {
                require(GameFeature.IF, token); condition(scope, body); block(scope, body);
                if (at("else")) { require(GameFeature.ELSE, take()); block(scope, body); }
            } else if (at("do") && version == LanguageVersion.STRUCTURED) {
                take();
                require(GameFeature.DO_WHILE, token); block(scope, body); need("while"); condition(scope, body); need(";");
            } else if (at("do") && position + 1 < tokens.size() && tokens.get(position + 1).text.equals("{")) {
                requireAvailable(GameFeature.DO_WHILE, token);
                throw new Failure("do/while requires the structured language version.", token);
            } else if (accept(";")) require(GameFeature.EMPTY_STATEMENTS, token);
            else {
                // The macro exercise asks for a definition, not merely a helper call.
                requireAvailable(GameFeature.MACROS, token);
                Lexeme name = identifier();
                need("(");
                int arity = 0;
                List<Value> arguments = new ArrayList<>();
                if (!at(")")) {
                    do {
                        if (++arity > MAX_ARITY) throw new Failure("Too many macro arguments for a game check (maximum " + MAX_ARITY + ").", peek());
                        Value argument = value(scope, false);
                        arguments.add(argument);
                        if (argument.literal()) {
                            int literal = number(argument.token);
                            if (literal > MAX_LOCAL_LITERAL) throw new Failure("Numeric macro arguments are limited to " + MAX_LOCAL_LITERAL + " in game checks.", argument.token);
                        }
                    } while (accept(","));
                }
                need(")"); need(";");
                body.calls.add(new Call(new Signature(name.text, arity), List.copyOf(arguments), nesting, name));
            }
        }
    }
}
