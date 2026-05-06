package sk.ainet.demo;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny recursive-descent expression evaluator backing {@link CalculatorTool}.
 * Supports {@code + - * /} with the usual precedence, parentheses, and unary
 * minus. Numbers are parsed as doubles. Mirrors the upstream Kotlin
 * {@code CalculatorTool} parser so the demo behaves identically.
 *
 * <p>Usage:
 * <pre>
 *   double result = Calc.evaluate("2 + 3 * (4 - 1)");
 * </pre>
 */
final class Calc {

    private final List<String> tokens;
    private int pos;

    private Calc(List<String> tokens) {
        this.tokens = tokens;
    }

    static double evaluate(String expression) {
        Calc parser = new Calc(tokenize(expression));
        double result = parser.parseExpression();
        if (parser.pos < parser.tokens.size()) {
            throw new IllegalArgumentException(
                "unexpected token: " + parser.tokens.get(parser.pos));
        }
        return result;
    }

    private static List<String> tokenize(String expr) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || c == '.') {
                int start = i;
                while (i < expr.length()
                    && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) {
                    i++;
                }
                out.add(expr.substring(start, i));
            } else if ("+-*/()".indexOf(c) >= 0) {
                out.add(String.valueOf(c));
                i++;
            } else {
                throw new IllegalArgumentException("unexpected character: " + c);
            }
        }
        return out;
    }

    private double parseExpression() {
        double result = parseTerm();
        while (pos < tokens.size()
            && (tokens.get(pos).equals("+") || tokens.get(pos).equals("-"))) {
            String op = tokens.get(pos++);
            double right = parseTerm();
            result = op.equals("+") ? result + right : result - right;
        }
        return result;
    }

    private double parseTerm() {
        double result = parseFactor();
        while (pos < tokens.size()
            && (tokens.get(pos).equals("*") || tokens.get(pos).equals("/"))) {
            String op = tokens.get(pos++);
            double right = parseFactor();
            result = op.equals("*") ? result * right : result / right;
        }
        return result;
    }

    private double parseFactor() {
        if (pos >= tokens.size()) {
            throw new IllegalArgumentException("unexpected end of expression");
        }
        String t = tokens.get(pos);
        if (t.equals("(")) {
            pos++;
            double result = parseExpression();
            if (pos >= tokens.size() || !tokens.get(pos).equals(")")) {
                throw new IllegalArgumentException("missing closing ')'");
            }
            pos++;
            return result;
        }
        if (t.equals("-")) {
            pos++;
            return -parseFactor();
        }
        pos++;
        return Double.parseDouble(t);
    }
}
