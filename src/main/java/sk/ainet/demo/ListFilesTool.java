package sk.ainet.demo;

import sk.ainet.apps.kllama.chat.ToolDefinition;
import sk.ainet.apps.kllama.chat.java.JavaTool;
import sk.ainet.apps.kllama.chat.java.JavaTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link JavaTool} that lists the contents of a directory.
 *
 * <p>Returns one entry per line; subdirectories are marked with a trailing
 * {@code /}. Demonstrates the simplest shape of a {@link JavaTool}: a
 * {@link ToolDefinition} built via {@link JavaTools#definition} and an
 * {@link #execute(Map)} that takes plain Java arg types decoded from the
 * model's JSON output.
 */
final class ListFilesTool implements JavaTool {

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
        DemoLog.stage("tool exec list_files(path=" + dir + ")");
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
}
