package sk.ainet.demo;

/**
 * Tiny logging helpers shared by the demo's main flow, tool implementations,
 * and {@link VerboseAgentListener}. Kept package-private so the public API of
 * this demo is just {@link Main}.
 */
final class DemoLog {

    private DemoLog() {
        // statics only
    }

    /**
     * Print a stage marker on its own blank line, e.g.
     * {@code >>> calling AgentLoop.runWithEncoder(...)}.
     */
    static void stage(String msg) {
        System.out.println();
        System.out.println(">>> " + msg);
    }

    /**
     * Indent each line of {@code s} by four spaces — used to visually nest
     * multi-line content (rendered prompts, tool results, assistant messages)
     * underneath the preceding {@link #stage} marker.
     */
    static String indent(String s) {
        return s.replaceAll("(?m)^", "    ");
    }
}
