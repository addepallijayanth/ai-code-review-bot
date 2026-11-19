package com.example.reviewbot.review;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Gitignore-style path globs for {@code ignore:} in {@code .reviewbot.yml}:
 * {@code *} matches within one path segment, {@code **} across segments, {@code ?} one character,
 * {@code {a,b}} either alternative. A pattern without a slash matches in any directory; a leading
 * slash anchors it to the repository root; a trailing slash matches everything below a directory.
 */
public final class Glob {

    private static final String REGEX_SPECIAL = "\\.[]()+-^$|";

    private final String source;
    private final Pattern regex;

    private Glob(String source, Pattern regex) {
        this.source = source;
        this.regex = regex;
    }

    public static Glob compile(String glob) {
        return new Glob(glob, Pattern.compile(toRegex(glob)));
    }

    public static List<Glob> compileAll(List<String> globs) {
        return globs.stream().map(Glob::compile).toList();
    }

    public static boolean anyMatches(List<Glob> globs, String path) {
        return globs.stream().anyMatch(glob -> glob.matches(path));
    }

    public boolean matches(String path) {
        return regex.matcher(path).matches();
    }

    public String source() {
        return source;
    }

    static String toRegex(String glob) {
        String g = glob.trim();
        if (g.startsWith("/")) {
            g = g.substring(1);
        } else if (!g.contains("/") || g.indexOf('/') == g.length() - 1) {
            g = "**/" + g;
        }
        if (g.endsWith("/")) {
            g = g + "**";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < g.length() && g.charAt(i + 1) == '*';
                if (doubleStar && i + 2 < g.length() && g.charAt(i + 2) == '/') {
                    out.append("(?:.*/)?");
                    i += 2;
                } else if (doubleStar) {
                    out.append(".*");
                    i += 1;
                } else {
                    out.append("[^/]*");
                }
            } else if (c == '?') {
                out.append("[^/]");
            } else if (c == '{' && g.indexOf('}', i) > i) {
                int close = g.indexOf('}', i);
                String[] alternatives = g.substring(i + 1, close).split(",", -1);
                out.append("(?:");
                for (int a = 0; a < alternatives.length; a++) {
                    if (a > 0) {
                        out.append('|');
                    }
                    out.append(Pattern.quote(alternatives[a]));
                }
                out.append(')');
                i = close;
            } else {
                if (REGEX_SPECIAL.indexOf(c) >= 0 || c == '{' || c == '}') {
                    out.append('\\');
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return source;
    }
}
