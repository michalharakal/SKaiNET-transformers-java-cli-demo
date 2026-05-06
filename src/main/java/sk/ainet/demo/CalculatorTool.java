package sk.ainet.demo;

import sk.ainet.apps.kllama.chat.ToolDefinition;
import sk.ainet.apps.kllama.chat.java.JavaTool;
import sk.ainet.apps.kllama.chat.java.JavaTools;

import java.util.Map;

/**
 * {@link JavaTool} that evaluates a simple arithmetic expression. Supports
 * {@code + - * /} and parentheses; delegates parsing to {@link Calc}.
 *
 * <p>Reimplements (rather than reuses) the upstream Kotlin
 * {@code sk.ainet.apps.kllama.cli.CalculatorTool} so the demo stays decoupled
 * from the kllama-cli internal package.
 */
final class CalculatorTool implements JavaTool {

    @Override
    public ToolDefinition getDefinition() {
        return JavaTools.definition(
            "calculator",
            "Evaluate a mathematical expression. Supports +, -, *, / and parentheses.",
            "{\"type\":\"object\","
                + "\"properties\":{\"expression\":{\"type\":\"string\","
                + "\"description\":\"The mathematical expression to evaluate, e.g. '2 + 3 * 4'.\"}},"
                + "\"required\":[\"expression\"]}"
        );
    }

    @Override
    public String execute(Map<String, ?> arguments) {
        Object exprObj = arguments.get("expression");
        if (exprObj == null) return "error: missing expression";
        String expr = exprObj.toString();
        DemoLog.stage("tool exec calculator(expression=" + expr + ")");
        try {
            return String.valueOf(Calc.evaluate(expr));
        } catch (RuntimeException e) {
            return "error: " + e.getMessage();
        }
    }
}
