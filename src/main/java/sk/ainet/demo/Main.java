package sk.ainet.demo;

import kotlin.jvm.functions.Function1;

import sk.ainet.apps.kllama.chat.AgentConfig;
import sk.ainet.apps.kllama.chat.AgentListener;
import sk.ainet.apps.kllama.chat.AgentLoop;
import sk.ainet.apps.kllama.chat.ChatMessage;
import sk.ainet.apps.kllama.chat.ChatRole;
import sk.ainet.apps.kllama.chat.ChatTemplate;
import sk.ainet.apps.kllama.chat.ModelMetadata;
import sk.ainet.apps.kllama.chat.ToolCallingSupport;
import sk.ainet.apps.kllama.chat.ToolCallingSupportResolver;
import sk.ainet.apps.kllama.chat.ToolDefinition;
import sk.ainet.apps.kllama.chat.ToolRegistry;
import sk.ainet.apps.kllama.chat.java.JavaToolAdapter;
import sk.ainet.apps.kllama.java.KLlamaJava;
import sk.ainet.apps.kllama.java.KLlamaSession;
import sk.ainet.apps.llm.Tokenizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static sk.ainet.demo.DemoLog.indent;
import static sk.ainet.demo.DemoLog.stage;

/**
 * Llama tool-calling demo with verbose stage-by-stage logging.
 *
 * <p>Bypasses {@code JavaAgentLoop} (whose Java surface only exposes a
 * Consumer&lt;String&gt; for streamed tokens) and drives the underlying
 * {@link AgentLoop} directly so we can attach a {@link VerboseAgentListener}
 * that logs every stage: prefill progress, rendered prompt fed to the
 * tokenizer, parsed tool calls, validation failures, tool execution and
 * result, and the final assistant message.
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
 *        path/to/model.gguf 'What is 17 * 23?'
 * </pre>
 */
public final class Main {

    private static final String SYSTEM_PROMPT = "You are a helpful assistant with two tools: "
        + "list_files (list a directory's contents) and calculator (evaluate +,-,*,/,()). "
        + "Pick the appropriate tool for the user's question and emit exactly one tool call "
        + "as raw JSON on a single line, with NO markdown fences and NO surrounding prose, e.g. "
        + "{\"name\":\"list_files\",\"parameters\":{\"path\":\".\"}} or "
        + "{\"name\":\"calculator\",\"parameters\":{\"expression\":\"2 + 3 * 4\"}}. "
        + "After receiving the tool result, summarize the answer in one short sentence.";

    private Main() {
        // utility entry point
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println(
                "Usage: skainet-java-demo <model.gguf> [prompt]\n"
                + "Example:\n"
                + "  skainet-java-demo Llama-3.2-1B-Instruct-Q8_0.gguf 'What is 17 * 23?'"
            );
            System.exit(1);
        }

        Path modelPath = Path.of(args[0]);
        String userPrompt = args.length >= 2
            ? args[1]
            : "List the files in the current directory.";

        stage("loading GGUF: " + modelPath);

        try (KLlamaSession session = KLlamaJava.loadGGUF(modelPath, /* systemPrompt */ null)) {

            ToolRegistry registry = new ToolRegistry();
            registry.register(new JavaToolAdapter(new ListFilesTool()));
            registry.register(new JavaToolAdapter(new CalculatorTool()));
            stage("registered " + registry.definitions().size() + " tool(s): "
                + registry.definitions().stream()
                    .map(ToolDefinition::getName).collect(Collectors.joining(", ")));

            // Resolve tool-calling support → chat template (same path JavaAgentLoop uses).
            ModelMetadata metadata = new ModelMetadata();
            ToolCallingSupport support =
                ToolCallingSupportResolver.INSTANCE.resolveOrFallback(metadata, "llama3");
            ChatTemplate template = support.createChatTemplate();
            stage("ToolCallingSupport family=" + support.getFamily()
                + " mode=" + support.toolCallingMode(metadata)
                + " template=" + template.getClass().getSimpleName());

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT, null, null));
            messages.add(new ChatMessage(ChatRole.USER, userPrompt, null, null));
            stage("system prompt:");
            System.out.println(indent(SYSTEM_PROMPT));
            stage("user prompt:");
            System.out.println(indent(userPrompt));

            // Render the FULL formatted prompt (chat-template tokens + tool-schema preamble)
            // — this is the exact text fed to the tokenizer for prefill.
            String renderedPrompt = template.apply(
                messages, registry.definitions(), /* addGenerationPrompt */ true);
            stage("rendered prompt (" + renderedPrompt.length() + " chars):");
            System.out.println(indent(renderedPrompt));

            // Pull tokenizer + runtime out of the session. These are Kotlin
            // `internal` accessors mangled to public on the JVM; safe to call from Java.
            Tokenizer tokenizer = session.getTokenizer$kllama();
            int[] promptTokens = tokenizer.encode(renderedPrompt);
            stage("tokenized prompt: " + promptTokens.length + " tokens (eos="
                + tokenizer.getEosTokenId() + ")");

            // Construct the AgentLoop ourselves so we can pass a rich listener.
            // Raw types because AgentLoop<T extends DType> and we don't want to
            // depend on sk.ainet.lang.types.FP32 from the demo.
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

            VerboseAgentListener listener = new VerboseAgentListener();

            stage("calling AgentLoop.runWithEncoder(...)");
            long startWallNanos = System.nanoTime();
            String finalResponse = (String) loop.runWithEncoder(messages, encode, (AgentListener) listener);
            long endWallNanos = System.nanoTime();

            int n = listener.tokenCount();
            double wallSec = (endWallNanos - startWallNanos) / 1e9;
            double decodeSec = (listener.lastTokenNanos() - listener.firstTokenNanos()) / 1e9;
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
}
