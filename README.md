# SKaiNET Java Demo

A minimal pure-Java tool-calling demo using
[SKaiNET-transformers](https://github.com/SKaiNET-developers/SKaiNET-transformers)
0.21.1. No Kotlin in your project, no Spring, no Python — just Maven + a
local GGUF model file.

The demo registers a `calculator` tool, asks a small Llama 3.x model
"What is 17 * 23?", and prints the model's tool-call → calculator
result → final answer chain.

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
specifically fine-tuned for the JSON tool-call format the demo prompts
for):

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
    -Dexec.args="-Xms2g -Xmx8g \
                 --enable-preview --add-modules jdk.incubator.vector \
                 -cp %classpath sk.ainet.demo.Main \
                 Llama-3.2-1B-Instruct-Q8_0.gguf 'What is 17 * 23?'"
```

Or directly with `java`:

```bash
java -Xms2g -Xmx8g \
     --enable-preview --add-modules jdk.incubator.vector \
     -jar target/skainet-java-demo-0.1.0-SNAPSHOT.jar \
     Llama-3.2-1B-Instruct-Q8_0.gguf 'What is 17 * 23?'
```

The `-Xms2g -Xmx8g` heap settings are required: the KV cache and
weight buffers don't fit in the JVM's ~256 MB default heap, and you
will get `OutOfMemoryError: Java heap space` during `loadGGUF` without
them.

Expected output (Llama 3.2 1B Instruct):

```
{"name": "calculator", "parameters": {"expression": "17 * 23"}}
---
Final answer: 17 * 23 = 391
```

The first line is the model's tool call (printed as it streams);
the calculator runs `17 * 23 = 391`, the result is fed back into the
model, and the final assistant message lands on the last line.

## What's happening under the hood

```
Main.main(args)
  └── KLlamaJava.loadGGUF(modelPath)
        └── KLlamaSession                    ← AutoCloseable; off-heap weights
              └── JavaAgentLoop.builder()
                    .tool(calculatorTool)    ← JavaTool: definition + execute(Map)
                    .build()
                    └── chat("What is 17 * 23?", System.out::print)
                          └── tokenize → forward → parse tool call →
                              run calculator → feed result back → final answer
```

`JavaTools.definition(name, description, jsonSchema)` is a static
factory that builds a `ToolDefinition` from a JSON-Schema string —
you don't need to import `kotlinx.serialization`.

## Troubleshooting

- **`Could not find artifact sk.ainet.transformers:skainet-transformers-runtime-kllama-jvm:jar:0.21.1`** —
  Maven Central indexes new releases on a delay. Wait a few hours after
  a SKaiNET-transformers tag push, then re-run with `mvn -U package`.
  The `pom.xml` here pins the exact published coordinates (long
  artifact names prefixed with `skainet-transformers-`); earlier
  iterations of this demo used short names that no longer match Central.
- **`UnsatisfiedLinkError: jdk.incubator.vector`** — JDK 21+ is required
  and you must pass `--enable-preview --add-modules jdk.incubator.vector`
  on the JVM command line. The pom does this for `mvn exec:java`; for
  `java -jar` you pass it directly.
- **`OutOfMemoryError: Java heap space` at `HeapKvCache.<init>`** —
  the JVM's default ~256 MB heap is too small for the KV cache. The
  example commands above already pass `-Xms2g -Xmx8g`; if you're
  invoking the jar from your own script, add the same flags.
- **Model not found / corrupt** — re-download with `curl -L -C -`
  (resume) and check the file size matches Hugging Face's listing.

## License

MIT. The demo code is yours to copy into a real project as a starting
point. SKaiNET-transformers itself is licensed under MIT.
