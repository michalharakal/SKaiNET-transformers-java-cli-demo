package sk.ainet.demo;

import sk.ainet.apps.kllama.chat.AgentListener;
import sk.ainet.apps.kllama.chat.ToolCall;

import java.util.List;

/**
 * {@link AgentListener} implementation that prints every stage of the agent
 * loop and records timing data for the post-run tok/s summary.
 *
 * <p>Three timing fields are tracked:
 * <ul>
 *   <li>{@link #firstTokenNanos()} — {@code System.nanoTime()} of the first
 *       generated (post-prefill) token.</li>
 *   <li>{@link #lastTokenNanos()} — {@code System.nanoTime()} of the last
 *       generated token.</li>
 *   <li>{@link #tokenCount()} — total count of generated tokens.</li>
 * </ul>
 *
 * <p>{@link Main} reads these after {@code AgentLoop.runWithEncoder} returns
 * to compute wall-clock and decode-only tokens-per-second.
 */
final class VerboseAgentListener implements AgentListener {

    private long firstTokenNanos = 0;
    private long lastTokenNanos = 0;
    private int tokenCount = 0;

    @Override
    public void onToken(String token) {
        long now = System.nanoTime();
        if (firstTokenNanos == 0) firstTokenNanos = now;
        lastTokenNanos = now;
        tokenCount++;
        System.out.print(token);
        System.out.flush();
    }

    @Override
    public void onPrefillProgress(int done, int total) {
        // Prefill is autoregressive in 0.23.x — one forward() per prompt
        // token before the first generated token. Report every 16 tokens
        // (and on the final token) on a single carriage-returned line so
        // the user sees the loop is alive instead of silently grinding.
        if (done == total || done % 16 == 0) {
            int pct = (int) Math.round(100.0 * done / total);
            System.out.printf("\r    prefill: %d/%d (%d%%)%s",
                done, total, pct, done == total ? "\n" : "");
            System.out.flush();
        }
    }

    @Override
    public void onAssistantMessage(String message) {
        DemoLog.stage("onAssistantMessage (" + message.length() + " chars)");
        System.out.println(DemoLog.indent(message));
    }

    @Override
    public void onThinking(String thinking) {
        DemoLog.stage("onThinking (" + thinking.length() + " chars)");
        System.out.println(DemoLog.indent(thinking));
    }

    @Override
    public void onToolCalls(List<ToolCall> calls) {
        DemoLog.stage("onToolCalls: parsed " + calls.size() + " call(s)");
        for (ToolCall c : calls) {
            System.out.println("    - " + c.getName() + " args=" + c.getArguments());
        }
    }

    @Override
    public void onToolResult(ToolCall call, String result) {
        DemoLog.stage("onToolResult " + call.getName() + " (" + result.length() + " chars):");
        System.out.println(DemoLog.indent(result));
    }

    @Override
    public void onToolCallValidationFailed(ToolCall call, String reason) {
        DemoLog.stage("!!! onToolCallValidationFailed " + call.getName() + ": " + reason);
    }

    @Override
    public void onComplete(String finalResponse) {
        DemoLog.stage("onComplete (" + finalResponse.length() + " chars)");
    }

    long firstTokenNanos() {
        return firstTokenNanos;
    }

    long lastTokenNanos() {
        return lastTokenNanos;
    }

    int tokenCount() {
        return tokenCount;
    }
}
