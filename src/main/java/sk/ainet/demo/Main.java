package sk.ainet.demo;

import kotlin.jvm.functions.Function1;

import sk.ainet.apps.kllama.chat.AgentConfig;
import sk.ainet.apps.kllama.chat.AgentListener;
import sk.ainet.apps.kllama.chat.AgentLoop;
import sk.ainet.apps.kllama.chat.ChatMessage;
import sk.ainet.apps.kllama.chat.ChatRole;
import sk.ainet.apps.kllama.chat.ChatTemplate;
import sk.ainet.apps.kllama.chat.ModelMetadata;
import sk.ainet.apps.kllama.chat.ToolCall;
import sk.ainet.apps.kllama.chat.ToolCallingSupport;
import sk.ainet.apps.kllama.chat.ToolCallingSupportResolver;
import sk.ainet.apps.kllama.chat.ToolDefinition;
import sk.ainet.apps.kllama.chat.ToolRegistry;
import sk.ainet.apps.kllama.chat.java.JavaTool;
import sk.ainet.apps.kllama.chat.java.JavaToolAdapter;
import sk.ainet.apps.kllama.chat.java.JavaTools;
import sk.ainet.apps.kllama.java.KLlamaJava;
import sk.ainet.apps.kllama.java.KLlamaSession;
import sk.ainet.apps.llm.Tokenizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Llama tool-calling demo with verbose stage-by-stage logging.
 *
 * <p>Bypasses {@code JavaAgentLoop} (whose Java surface only exposes a
 * Consumer&lt;String&gt; for streamed tokens) and drives the underlying
 * {@code AgentLoop} directly so we can attach an {@link AgentListener} that
 * logs every stage: rendered prompt fed to the tokenizer, parsed tool calls,
 * validation failures, tool execution and result, and the final assistant
 * message.
 *
 * <p>Run with:
 * <pre>
 *   mvn exec:java -Dexec.args="path/to/model.gguf 'List files in .'"
 * </pre>
 *
 * <p>Or after `mvn package`:
 * <pre>
 *   java --enable-preview --add-modules jdk.incubator.vector \
 *        -jar target/skainet-java-demo-0.1.0-SNAPSHOT.jar \
 *        path/to/model.gguf 'List files in .'
 * </pre>
 */
public final class Main {

    private Main() {
        // utility entry point
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println(
                "Usage: skainet-java-demo <model.gguf> [prompt]\n"
                + "Example:\n"
                + "  skainet-java-demo Llama-3.2-1B-Instruct-Q8_0.gguf 'List files in .'"
            );
            System.exit(1);
        }

        Path modelPath = Path.of(args[0]);
        String userPrompt = args.length >= 2
            ? args[1]
            : "List the files in the current directory.";

        // 1. Define the list_files tool.
        JavaTool listFiles = new JavaTool() {
            @Override
            public ToolDefinition getDefinition() {
                return JavaTools.definition(
                    "list_files",
                    "List the files and subdirectories of a directory on the local filesystem. "
                        + "Returns one entry per line, with a trailing '/' marking subdirectories.",
                    "{\"type\":\"object\","
                        + "\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"Directory path to list. Use '.' for the current working directory.\"}},"
                        + "\"required\":[\"path\"]}"
                );
            }

            @Override
            public String execute(Map<String, ?> arguments) {
                Object pathObj = arguments.get("path");
                if (pathObj == null) return "error: missing path";
                Path dir = Path.of(pathObj.toString()).toAbsolutePath().normalize();
                stage("tool exec list_files(path=" + dir + ")");
                if (!Files.isDirectory(dir)) {
                    return "error: not a directory: " + dir;
                }
                try (Stream<Path> entries = Files.list(dir)) {
                    String listing = entries
                        .sorted()
                        .map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : ""))
                        .collect(Collectors.joining("\n"));
                    return listing.isEmpty() ? "(empty directory)" : listing;
                } catch (IOException e) {
                    return "error: " + e.getMessage();
                }
            }
        };

        String systemPrompt = "You are a helpful assistant with filesystem access. "
            + "When the user asks about files or directory contents, emit exactly one "
            + "tool call as raw JSON on a single line, with NO markdown fences and NO "
            + "surrounding prose, e.g. "
            + "{\"name\":\"list_files\",\"parameters\":{\"path\":\".\"}} "
            + "After receiving the tool result, summarize what you found in one short sentence.";

        stage("loading GGUF: " + modelPath);

        try (KLlamaSession session = KLlamaJava.loadGGUF(modelPath, /* systemPrompt */ null)) {

            // 2. Tool registry.
            ToolRegistry registry = new ToolRegistry();
            registry.register(new JavaToolAdapter(listFiles));
            stage("registered " + registry.definitions().size() + " tool(s): "
                + registry.definitions().stream()
                    .map(ToolDefinition::getName).collect(Collectors.joining(", ")));

            // 3. Resolve tool-calling support → chat template.
            //    (The same resolution path JavaAgentLoop uses internally.)
            ModelMetadata metadata = new ModelMetadata();
            String templateName = "llama3";
            ToolCallingSupport support =
                ToolCallingSupportResolver.INSTANCE.resolveOrFallback(metadata, templateName);
            ChatTemplate template = support.createChatTemplate();
            stage("ToolCallingSupport family=" + support.getFamily()
                + " mode=" + support.toolCallingMode(metadata)
                + " template=" + template.getClass().getSimpleName());

            // 4. Build messages: system + user.
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage(ChatRole.SYSTEM, systemPrompt, null, null));
            messages.add(new ChatMessage(ChatRole.USER, userPrompt, null, null));
            stage("system prompt:");
            System.out.println(indent(systemPrompt));
            stage("user prompt:");
            System.out.println(indent(userPrompt));

            // 5. Render the FULL formatted prompt (with chat-template tokens
            //    + tool-schema preamble) — this is the exact text fed to the
            //    tokenizer for prefill.
            String renderedPrompt = template.apply(messages, registry.definitions(), /* addGenerationPrompt */ true);
            stage("rendered prompt (" + renderedPrompt.length() + " chars):");
            System.out.println(indent(renderedPrompt));

            // 6. Pull the tokenizer + runtime out of the session.
            //    These are Kotlin `internal` accessors mangled to public on the JVM
            //    with the module-name suffix; perfectly callable from Java.
            Tokenizer tokenizer = session.getTokenizer$kllama();
            int[] promptTokens = tokenizer.encode(renderedPrompt);
            stage("tokenized prompt: " + promptTokens.length + " tokens (eos="
                + tokenizer.getEosTokenId() + ")");

            // 7. Construct the AgentLoop ourselves so we can pass a rich listener.
            //    Raw types because AgentLoop<T extends DType> and we don't want
            //    to depend on sk.ainet.lang.types.FP32 from the demo.
            AgentConfig config = new AgentConfig();
            Function1<Integer, String> decode = new Function1<>() {
                @Override public String invoke(Integer id) { return tokenizer.decode(id); }
            };
            AgentLoop loop = new AgentLoop(
                session.getRuntime$kllama(),
                template,
                registry,
                tokenizer.getEosTokenId(),
                config,
                decode);

            Function1<String, int[]> encode = new Function1<>() {
                @Override public int[] invoke(String s) { return tokenizer.encode(s); }
            };

            // 8. Listener that logs every stage.
            final long[] firstTokenNanos = {0};
            final long[] lastTokenNanos = {0};
            final int[] tokenCount = {0};

            AgentListener listener = new AgentListener() {
                @Override
                public void onToken(String t) {
                    long now = System.nanoTime();
                    if (firstTokenNanos[0] == 0) firstTokenNanos[0] = now;
                    lastTokenNanos[0] = now;
                    tokenCount[0]++;
                    System.out.print(t);
                    System.out.flush();
                }

                @Override
                public void onAssistantMessage(String message) {
                    stage("onAssistantMessage (" + message.length() + " chars)");
                    System.out.println(indent(message));
                }

                @Override
                public void onThinking(String thinking) {
                    stage("onThinking (" + thinking.length() + " chars)");
                    System.out.println(indent(thinking));
                }

                @Override
                public void onToolCalls(List<ToolCall> calls) {
                    stage("onToolCalls: parsed " + calls.size() + " call(s)");
                    for (ToolCall c : calls) {
                        System.out.println("    - " + c.getName()
                            + " args=" + c.getArguments());
                    }
                }

                @Override
                public void onToolResult(ToolCall call, String result) {
                    stage("onToolResult " + call.getName() + " (" + result.length() + " chars):");
                    System.out.println(indent(result));
                }

                @Override
                public void onToolCallValidationFailed(ToolCall call, String reason) {
                    stage("!!! onToolCallValidationFailed " + call.getName() + ": " + reason);
                }

                @Override
                public void onComplete(String finalResponse) {
                    stage("onComplete (" + finalResponse.length() + " chars)");
                }
            };

            // 9. Run the agent loop.
            stage("calling AgentLoop.runWithEncoder(...)");
            long startWallNanos = System.nanoTime();
            String finalResponse = (String) loop.runWithEncoder(messages, encode, listener);
            long endWallNanos = System.nanoTime();

            int n = tokenCount[0];
            double wallSec = (endWallNanos - startWallNanos) / 1e9;
            double decodeSec = (lastTokenNanos[0] - firstTokenNanos[0]) / 1e9;
            double wallTps = wallSec > 0 ? n / wallSec : 0;
            double decodeTps = (n > 1 && decodeSec > 0) ? (n - 1) / decodeSec : 0;

            System.out.println();
            System.out.println("---");
            System.out.println("Final answer: " + finalResponse);
            System.out.printf(
                "[%d tokens — wall %.2fs (%.2f tok/s), decode %.2fs (%.2f tok/s)]%n",
                n, wallSec, wallTps, decodeSec, decodeTps);
        }
    }

    private static void stage(String msg) {
        System.out.println();
        System.out.println(">>> " + msg);
    }

    private static String indent(String s) {
        return s.replaceAll("(?m)^", "    ");
    }
}
