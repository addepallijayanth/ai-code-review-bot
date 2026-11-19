package com.example.reviewbot.review;

import com.example.reviewbot.model.ReviewFinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Merges findings that describe the same problem: overlapping chunks, the secret scanner and the
 * model flagging the same line, or two phrasings of one issue. Also computes the fingerprint used
 * to avoid re-posting a finding on a later commit of the same pull request.
 */
@Component
public class FindingDeduplicator {

    private static final double SIMILAR_TITLE = 0.5;

    /** The most severe finding of each duplicate group, picking up a suggestion from a duplicate if it lacks one. */
    public List<ReviewFinding> dedupe(List<ReviewFinding> findings) {
        List<ReviewFinding> ordered = findings.stream()
                .sorted(Comparator.comparingInt((ReviewFinding f) -> -f.severity().rank())
                        .thenComparing(f -> f.source() == ReviewFinding.Source.AGENT ? 0 : 1)
                        .thenComparing(ReviewFinding::file)
                        .thenComparingInt(ReviewFinding::line))
                .toList();
        List<ReviewFinding> kept = new ArrayList<>();
        for (ReviewFinding candidate : ordered) {
            int duplicateOf = -1;
            for (int i = 0; i < kept.size(); i++) {
                if (isDuplicate(kept.get(i), candidate)) {
                    duplicateOf = i;
                    break;
                }
            }
            if (duplicateOf < 0) {
                kept.add(candidate);
                continue;
            }
            ReviewFinding existing = kept.get(duplicateOf);
            if (existing.suggestion() == null && candidate.suggestion() != null && existing.line() == candidate.line()
                    && Objects.equals(existing.startLine(), candidate.startLine())) {
                kept.set(duplicateOf, existing.withSuggestion(candidate.suggestion()));
            }
        }
        return kept;
    }

    static boolean isDuplicate(ReviewFinding a, ReviewFinding b) {
        return a.file().equals(b.file())
                && Math.abs(a.line() - b.line()) <= 1
                && (a.category() == b.category() || titleSimilarity(a.title(), b.title()) >= SIMILAR_TITLE);
    }

    /**
     * Stable identity of a finding across commits: file, category and the text of the commented line
     * (not its number, which shifts when code above it changes).
     */
    public String fingerprint(ReviewFinding finding) {
        String content = normalize(finding.lineContent());
        String basis = finding.file() + "\n" + finding.category().slug() + "\n"
                + (content.isEmpty() ? "line:" + finding.line() : content);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static double titleSimilarity(String a, String b) {
        Set<String> left = words(a);
        Set<String> right = words(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        long common = left.stream().filter(right::contains).count();
        return (double) common / (left.size() + right.size() - common);
    }

    private static Set<String> words(String text) {
        if (text == null) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(word -> word.length() >= 3)
                .collect(Collectors.toSet());
    }

    private static String normalize(String line) {
        return line == null ? "" : line.strip().replaceAll("\\s+", " ");
    }
}
