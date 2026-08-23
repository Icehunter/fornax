package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A boolean expression over compile-option values: integer/boolean literals and option names, with
 * {@code == != < > && || !} and parentheses. Bools are 0/1; a bare option name is truthy when nonzero.
 * Recursive-descent; no runtime state, safe to run against any value map.
 */
public final class EnabledIfExpr {
    private final Node root;
    private final Set<String> names;

    private EnabledIfExpr(Node root, Set<String> names) {
        this.root = root;
        this.names = names;
    }

    public static EnabledIfExpr parse(String source) {
        Parser p = new Parser(tokenize(source), source);
        Node n = p.parseOr();
        p.expectEnd();
        return new EnabledIfExpr(n, p.names);
    }

    public boolean evaluate(Map<String, Integer> compileValues) {
        return root.run(compileValues) != 0;
    }

    public Set<String> referencedNames() {
        return names;
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isLetterOrDigit(c) || c == '_') {
                int j = i + 1;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                out.add(s.substring(i, j));
                i = j;
            } else if (c == '&' || c == '|') {
                if (i + 1 < s.length() && s.charAt(i + 1) == c) { out.add("" + c + c); i += 2; }
                else throw new FornaxPackError("enabled_if", s, "expected '" + c + c + "'");
            } else if (c == '=' && i + 1 < s.length() && s.charAt(i + 1) == '=') {
                out.add("=="); i += 2;
            } else if (c == '!' && i + 1 < s.length() && s.charAt(i + 1) == '=') {
                out.add("!="); i += 2;
            } else if ("<>!()".indexOf(c) >= 0) {
                out.add(String.valueOf(c)); i++;
            } else {
                throw new FornaxPackError("enabled_if", s, "unexpected character '" + c + "'");
            }
        }
        return out;
    }

    private interface Node { int run(Map<String, Integer> v); }

    private static final class Parser {
        private final List<String> tokens;
        private final String source;
        private final Set<String> names = new LinkedHashSet<>();
        private int pos;

        Parser(List<String> tokens, String source) { this.tokens = tokens; this.source = source; }

        Node parseOr() {
            Node left = parseAnd();
            while (peek("||")) { next(); Node r = parseAnd(); Node l = left; left = v -> (l.run(v) != 0 || r.run(v) != 0) ? 1 : 0; }
            return left;
        }

        Node parseAnd() {
            Node left = parseCompare();
            while (peek("&&")) { next(); Node r = parseCompare(); Node l = left; left = v -> (l.run(v) != 0 && r.run(v) != 0) ? 1 : 0; }
            return left;
        }

        Node parseCompare() {
            Node left = parseUnary();
            if (peek("==") || peek("!=") || peek("<") || peek(">")) {
                String op = next();
                Node r = parseUnary();
                Node l = left;
                return v -> switch (op) {
                    case "==" -> l.run(v) == r.run(v) ? 1 : 0;
                    case "!=" -> l.run(v) != r.run(v) ? 1 : 0;
                    case "<"  -> l.run(v) < r.run(v) ? 1 : 0;
                    default   -> l.run(v) > r.run(v) ? 1 : 0;
                };
            }
            return left;
        }

        Node parseUnary() {
            if (peek("!")) { next(); Node inner = parseUnary(); return v -> inner.run(v) == 0 ? 1 : 0; }
            return parseAtom();
        }

        Node parseAtom() {
            if (peek("(")) { next(); Node inner = parseOr(); expect(")"); return inner; }
            String tok = next();
            if (tok == null) throw err("unexpected end of expression");
            if (tok.equals("true")) return v -> 1;
            if (tok.equals("false")) return v -> 0;
            if (tok.chars().allMatch(Character::isDigit)) { int lit = Integer.parseInt(tok); return v -> lit; }
            if (Character.isLetter(tok.charAt(0)) || tok.charAt(0) == '_') {
                names.add(tok);
                return v -> v.getOrDefault(tok, 0);
            }
            throw err("unexpected token '" + tok + "'");
        }

        boolean peek(String t) { return pos < tokens.size() && tokens.get(pos).equals(t); }
        String next() { return pos < tokens.size() ? tokens.get(pos++) : null; }
        void expect(String t) { if (!peek(t)) throw err("expected '" + t + "'"); pos++; }
        void expectEnd() { if (pos != tokens.size()) throw err("trailing tokens after expression"); }
        FornaxPackError err(String why) { return new FornaxPackError("enabled_if", source, why); }
    }
}
