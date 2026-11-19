package com.example.reviewbot.review;

import static java.util.Map.entry;

import java.util.Locale;
import java.util.Map;

/** Maps a file path to the language key used by {@code language_rules} in {@code .reviewbot.yml}. */
public final class Languages {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            entry("java", "java"), entry("kt", "kotlin"), entry("kts", "kotlin"), entry("scala", "scala"),
            entry("groovy", "groovy"), entry("gradle", "groovy"), entry("py", "python"), entry("rb", "ruby"),
            entry("go", "go"), entry("rs", "rust"), entry("php", "php"), entry("js", "javascript"),
            entry("jsx", "javascript"), entry("mjs", "javascript"), entry("cjs", "javascript"),
            entry("ts", "typescript"), entry("tsx", "typescript"), entry("cs", "csharp"), entry("swift", "swift"),
            entry("c", "c"), entry("h", "c"), entry("cc", "cpp"), entry("cpp", "cpp"), entry("hpp", "cpp"),
            entry("m", "objective-c"), entry("dart", "dart"), entry("ex", "elixir"), entry("exs", "elixir"),
            entry("sql", "sql"), entry("sh", "shell"), entry("bash", "shell"), entry("ps1", "powershell"),
            entry("yml", "yaml"), entry("yaml", "yaml"), entry("json", "json"), entry("xml", "xml"),
            entry("tf", "terraform"), entry("html", "html"), entry("css", "css"), entry("scss", "css"),
            entry("vue", "vue"), entry("svelte", "svelte"), entry("md", "markdown"));

    private Languages() {
    }

    public static String of(String path) {
        String name = fileName(path);
        if (name.equals("Dockerfile") || name.startsWith("Dockerfile.")) {
            return "dockerfile";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "text";
        }
        return BY_EXTENSION.getOrDefault(name.substring(dot + 1).toLowerCase(Locale.ROOT), "text");
    }

    public static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
