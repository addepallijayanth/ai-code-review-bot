package com.example.reviewbot.ai.tools;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Finds test files for a source file by common naming conventions across languages. */
public final class TestLocator {

    private TestLocator() {
    }

    public static boolean isTestFile(String path) {
        String normalized = path.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        String name = fileName(normalized);
        String base = stripExtension(name);
        return lower.contains("/test/") || lower.contains("/tests/") || lower.contains("/__tests__/")
                || lower.contains("/spec/") || lower.startsWith("test/") || lower.startsWith("tests/")
                || lower.startsWith("spec/")
                || base.endsWith("Test") || base.endsWith("Tests") || isIntegrationTestName(base) || base.endsWith("Spec")
                || base.startsWith("test_") || base.endsWith("_test") || base.endsWith("_spec")
                || name.contains(".test.") || name.contains(".spec.");
    }

    /** Test files whose name matches {@code sourcePath}'s, closest directory first. */
    public static List<String> relatedTests(String sourcePath, Collection<String> allPaths, int limit) {
        String base = stripExtension(fileName(sourcePath)).toLowerCase(Locale.ROOT);
        Set<String> candidates = Set.of(
                base + "test", base + "tests", base + "it", base + "spec", "test" + base, "test_" + base,
                base + "_test", base + "_spec", base + ".test", base + ".spec");
        return allPaths.stream()
                .filter(path -> !path.equals(sourcePath))
                .filter(TestLocator::isTestFile)
                .filter(path -> candidates.contains(stripExtension(fileName(path)).toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt((String path) -> -commonPrefix(path, sourcePath)).thenComparing(path -> path))
                .limit(limit)
                .toList();
    }

    /** {@code OrderServiceIT}, but not {@code AUDIT}. */
    private static boolean isIntegrationTestName(String base) {
        return base.length() > 2 && base.endsWith("IT") && Character.isLowerCase(base.charAt(base.length() - 3));
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static int commonPrefix(String a, String b) {
        int length = Math.min(a.length(), b.length());
        int i = 0;
        while (i < length && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }
}
