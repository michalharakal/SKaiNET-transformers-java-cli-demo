# SKaiNET Java Demo

A minimal pure-Java tool-calling demo using
[SKaiNET-transformers](https://github.com/SKaiNET-developers/SKaiNET-transformers)
0.23.2. No Kotlin in your project, no Spring, no Python — just Maven + a
local GGUF model file.

The demo registers a `list_files` tool that reads a directory off the
local filesystem, asks a small Llama 3.x model to "List the files in the
current directory", and prints every stage of the agent loop:
rendered prompt fed to the tokenizer, parsed tool calls, validation
failures, tool dispatch + result, and the final assistant message.

The verbose logging is wired by driving the underlying `AgentLoop`
directly with a custom `AgentListener`, instead of using `JavaAgentLoop`
whose Java surface only exposes a `Consumer<String>` for streamed
tokens.

## What you need

- **JDK 21 or newer.** Java 25 preferred (the runtime uses the Vector
  API as an incubator module). Check: `java -version`.
- **Maven 3.9+.** Check: `mvn -v`.
- **About 1.5 GB of disk** for the model file.
- **~4 GB free RAM** to load + run the model.

No Python required.

## 1. Download a model from Hugging Face (curl, no Python)

Hugging Face hosts model files behind regular HTTPS URLs of the form
`https://huggingface.co/<org>/<repo>/resolve/main/<file>`. `curl -L`
follows the redirects to the CDN.

Recommended for this demo — Llama 3.2 1B Instruct Q8 (~1.3 GB,
specifically fine-tuned for the JSON tool-call format the demo expects.
The Llama 3 chat template's `NATIVE` tool-calling mode emits one-line
`{"name":"...","parameters":{...}}` JSON, no `<|python_tag|>` prefix):

```bash
curl -L \
  -o Llama-3.2-1B-Instruct-Q8_0.gguf \
  "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q8_0.gguf"
```

(If `curl` reports a 401, the model is gated behind Hugging Face login.
Either follow `bartowski`'s mirror — the URL above already does — or
log in once on the Hugging Face website to accept the license, then
add `-H "Authorization: Bearer hf_yourtoken"` to the curl command.
Tokens come from <https://huggingface.co/settings/tokens>.)

Smaller fallback that does NOT require a Hugging Face account —
TinyLlama 1.1B Q8 (~1.1 GB):

```bash
curl -L \
  -o tinyllama-1.1b-chat-v1.0.Q8_0.gguf \
  "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q8_0.gguf"
```

TinyLlama runs but is not fine-tuned for tool calling — it tends to
hallucinate the answer rather than invoke the tool. Use Llama 3.2 1B
to actually exercise the tool path.

## 2. Build

```bash
mvn -B package
```

Produces `target/skainet-java-demo-0.1.0-SNAPSHOT.jar` — a
self-contained runnable jar with all SKaiNET classes shaded in.

## 3. Run — minimal version

Either via Maven:

```bash
mvn -B exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-Xms2g -Xmx16g \
                 --enable-preview --add-modules jdk.incubator.vector \
                 -cp %classpath sk.ainet.demo.Main \
                 Llama-3.2-1B-Instruct-Q8_0.gguf 'List files in .'"
```

Or directly with `java`:

```bash
java -Xms2g -Xmx16g \
     --enable-preview --add-modules jdk.incubator.vector \
     -jar target/skainet-java-demo-0.1.0-SNAPSHOT.jar \
     Llama-3.2-1B-Instruct-Q8_0.gguf 'List files in .'
```

The `-Xms2g -Xmx16g` heap settings are required: in
SKaiNET-transformers 0.23.2 `KLlamaJava.loadGGUF` allocates the KV
cache for the model's full context length up front, which for
Llama 3.2 1B is 131 072 tokens — about 8 GB of FloatArrays just for
K and V buffers, before any inference work. Without `-Xmx16g` you
will see `OutOfMemoryError: Java heap space` at
`HeapKvCache.<init>` during `loadGGUF`. A future SKaiNET-transformers
release will expose a `maxContextLength` knob so the cache can be
clamped (e.g. to 4096) and the heap requirement drops back below
1 GB.

Expected output (Llama 3.2 1B Instruct), abbreviated:

```
>>> ToolCallingSupport family=llama3 mode=NATIVE template=Llama3ChatTemplate
>>> rendered prompt (1401 chars):
    <|begin_of_text|><|start_header_id|>system<|end_header_id|>
    You are a helpful assistant with tool calling capabilities.
    ...
>>> tokenized prompt: 282 tokens (eos=128009)
>>> calling AgentLoop.runWithEncoder(...)
{"name":"list_files","parameters":{"path":"."}}
>>> onAssistantMessage (47 chars)
>>> onToolCalls: parsed 1 call(s)
    - list_files args={"path":"."}
>>> tool exec list_files(path=/home/.../SKaiNET-java-demo)
>>> onToolResult list_files (64 chars):
    .git/
    .gitignore
    LICENSE
    README.md
    pom.xml
    src/
    target/
... [model summarizes] ...
---
Final answer: The current directory contains README.md, pom.xml, src/, ...
```

The streamed JSON line is the model's tool call (printed token-by-token
via `onToken`); `onToolCalls` fires once the chat template has parsed
the JSON; the `list_files` tool runs and the directory listing is fed
back into the model; the final assistant message lands on the last line.

## What's happening under the hood

```
Main.main(args)
  └── KLlamaJava.loadGGUF(modelPath)
        └── KLlamaSession                    ← AutoCloseable; off-heap weights
              ├── ToolRegistry.register(JavaToolAdapter(listFiles))
              ├── ToolCallingSupportResolver.resolveOrFallback(metadata, "llama3")
              │     └── Llama3ChatTemplate (NATIVE tool-calling mode)
              └── new AgentLoop(runtime, template, registry, eos, config, decode)
                    └── runWithEncoder(messages, encode, listener)
                          ├── runtime.reset()                    ← KV cache cleared
                          ├── template.apply(messages, defs, true) → rendered prompt
                          ├── encode(rendered) → token IDs
                          ├── generateUntilStop → onToken stream
                          ├── template.parseToolCalls(text) → onToolCalls
                          ├── tool.execute(args) → onToolResult
                          └── append tool message; loop until no more tool calls
```

`JavaTools.definition(name, description, jsonSchema)` is a static
factory that builds a `ToolDefinition` from a JSON-Schema string —
you don't need to import `kotlinx.serialization`.

The demo bypasses `JavaAgentLoop` (whose `Builder` only lets you wire
a `Consumer<String>` for streamed tokens) and constructs `AgentLoop`
directly so we can attach an `AgentListener` with rich per-stage
callbacks: `onToken`, `onAssistantMessage`, `onThinking`, `onToolCalls`,
`onToolResult`, `onToolCallValidationFailed`, `onComplete`.

### Per-turn re-prefill

`AgentLoop.runWithEncoder` calls `runtime.reset()` at the top of every
tool round — the KV cache is discarded between rounds. So after the
first tool call resolves, round 2 re-renders and re-tokenizes the full
conversation (system prompt + tool schema + user message + assistant
tool call + tool result + new assistant header) and prefills it from
token 0. On a CPU-only 1B Llama this can dominate wall time. If you
only need the tool call's output (not the model's natural-language
summary), set `AgentConfig.setMaxToolRounds(1)` to skip round 2.

## Troubleshooting

- **`Could not find artifact sk.ainet.transformers:skainet-transformers-runtime-kllama-jvm:jar:<version>`** —
  Maven Central indexes new releases on a delay. Wait a few hours after
  a SKaiNET-transformers tag push, then re-run with `mvn -U package`.
  The `pom.xml` here pins the exact published coordinates (long
  artifact names prefixed with `skainet-transformers-`); earlier
  iterations of this demo used short names that no longer match Central.
- **Model wraps tool call in markdown ` ``` ` fences and the loop hangs** —
  the parser only matches raw JSON. The system prompt in this demo
  explicitly tells the model "no markdown fences, no surrounding prose".
  If you swap in your own system prompt, keep that instruction or the
  model may emit fenced JSON that `parseToolCalls` skips, after which
  the model just keeps generating until hitting `maxTokensPerRound`.
- **Round 2 takes minutes on CPU** — `AgentLoop` resets the KV cache
  every tool round and re-prefills the full conversation. See "Per-turn
  re-prefill" above. Set `AgentConfig.setMaxToolRounds(1)` if you only
  want the tool's output and don't need the model's summary.
- **`UnsatisfiedLinkError: jdk.incubator.vector`** — JDK 21+ is required
  and you must pass `--enable-preview --add-modules jdk.incubator.vector`
  on the JVM command line. The pom does this for `mvn exec:java`; for
  `java -jar` you pass it directly.
- **`OutOfMemoryError: Java heap space` at `HeapKvCache.<init>`** —
  the KV cache is sized to the model's full context length (131 072
  tokens for Llama 3.2 1B = ~8 GB on heap). Pass `-Xms2g -Xmx16g` as
  the example commands do. `-Xmx8g` is *not* enough — it lands right
  at the edge and the JVM can't grow past it for the supporting
  allocations.
- **Model not found / corrupt** — re-download with `curl -L -C -`
  (resume) and check the file size matches Hugging Face's listing.

## License

MIT. The demo code is yours to copy into a real project as a starting
point. SKaiNET-transformers itself is licensed under MIT.
