package com.example.reviewbot.review;

import com.example.reviewbot.ai.FindingContext;
import com.example.reviewbot.ai.FixProposal;
import com.example.reviewbot.config.GitHubAppProperties;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Markdown for everything the bot posts: inline comments, the review summary, and command replies. */
@Component
public class ReviewCommentFormatter {

    /** GitHub rejects comment bodies over 65,536 characters. */
    private static final int MAX_BODY = 60_000;
    private static final int MAX_TABLE_ROWS = 50;
    private static final Pattern BACKTICK_RUN = Pattern.compile("`{3,}");

    private final String webUrl;
    private final String mention;

    public ReviewCommentFormatter(GitHubAppProperties github) {
        this.webUrl = github.webUrl().endsWith("/") ? github.webUrl().substring(0, github.webUrl().length() - 1) : github.webUrl();
        this.mention = github.mention();
    }

    public String inlineComment(long reviewId, NumberedFinding numbered) {
        ReviewFinding finding = numbered.finding();
        StringBuilder out = new StringBuilder();
        out.append(finding.severity().emoji()).append(" **").append(finding.severity().label()).append("** · ")
                .append(finding.category().label()).append("\n\n")
                .append("**").append(singleLine(finding.title())).append("**\n\n")
                .append(finding.message().strip()).append("\n\n");
        if (numbered.suggestionApplicable()) {
            out.append(fence("suggestion", finding.suggestion())).append("\n\n");
        } else if (finding.suggestion() != null) {
            out.append("Possible fix:\n\n").append(fence(codeLanguage(finding.file()), finding.suggestion())).append("\n\n");
        }
        out.append("<sub>#").append(numbered.ordinal()).append(" · Reply with `").append(mention)
                .append(" explain` or `").append(mention).append(" fix`</sub>\n")
                .append(FindingMarkers.finding(reviewId, numbered.ordinal()));
        return out.toString();
    }

    /**
     * The review body. With {@code inlinePosted} false (GitHub refused the inline comments) every
     * finding is written out in full in the summary.
     */
    public String summary(long reviewId, String repo, int prNumber, ReviewResult result, RepoConfig config,
                          String scopeNote, boolean inlinePosted) {
        StringBuilder out = new StringBuilder("## 🤖 ReviewBot\n\n");
        out.append(headline(result)).append("\n\n");
        if (scopeNote != null) {
            out.append("> ").append(scopeNote).append("\n\n");
        }

        List<NumberedFinding> findings = result.findings();
        if (!findings.isEmpty()) {
            out.append("| # | Severity | Location | Finding |\n|---|---|---|---|\n");
            for (NumberedFinding numbered : findings.subList(0, Math.min(findings.size(), MAX_TABLE_ROWS))) {
                ReviewFinding f = numbered.finding();
                out.append("| ").append(numbered.ordinal())
                        .append(" | ").append(f.severity().emoji()).append(' ').append(f.severity().label())
                        .append(" | ").append(location(repo, prNumber, f))
                        .append(" | ").append(tableCell(f.title()))
                        .append(" |\n");
            }
            if (findings.size() > MAX_TABLE_ROWS) {
                out.append("| … | | | ").append(findings.size() - MAX_TABLE_ROWS).append(" more |\n");
            }
            out.append('\n');
        }

        List<NumberedFinding> detailed = inlinePosted ? result.summaryOnly() : findings;
        if (!detailed.isEmpty()) {
            out.append("<details><summary>").append(detailed.size())
                    .append(detailed.size() == 1 ? " finding" : " findings")
                    .append(inlinePosted ? " without an inline comment" : " (details)").append("</summary>\n\n");
            for (NumberedFinding numbered : detailed) {
                ReviewFinding f = numbered.finding();
                out.append("**#").append(numbered.ordinal()).append(' ').append(f.severity().emoji()).append(' ')
                        .append(singleLine(f.title())).append("** · `").append(f.file()).append(':').append(f.line())
                        .append("`\n\n").append(f.message().strip()).append("\n\n");
                if (f.suggestion() != null) {
                    out.append(fence(codeLanguage(f.file()), f.suggestion())).append("\n\n");
                }
            }
            out.append("</details>\n\n");
        }

        if (!result.skippedFiles().isEmpty()) {
            out.append("<details><summary>Skipped ").append(result.skippedFiles().size())
                    .append(result.skippedFiles().size() == 1 ? " file" : " files").append("</summary>\n\n");
            for (FileFilter.SkippedFile skipped : result.skippedFiles()) {
                out.append("- `").append(skipped.path()).append("` — ").append(skipped.reason().label()).append('\n');
            }
            out.append("\n</details>\n\n");
        }

        List<String> notes = new ArrayList<>(result.notes());
        config.warnings().forEach(warning -> notes.add("`.reviewbot.yml`: " + warning));
        if (!notes.isEmpty()) {
            out.append("**Notes**\n\n");
            notes.forEach(note -> out.append("- ").append(note).append('\n'));
            out.append('\n');
        }

        out.append("<sub>Reply `").append(mention).append(" explain <#>` or `").append(mention)
                .append(" fix <#>` here, or reply to an inline comment. Settings live in `.reviewbot.yml`.</sub>\n")
                .append(FindingMarkers.review(reviewId));
        return truncateBody(out.toString(), FindingMarkers.review(reviewId));
    }

    /** "Reviewed 7 files · 🔴 1 critical · 🟠 2 major · 🟡 3 minor". */
    public String headline(ReviewResult result) {
        StringBuilder out = new StringBuilder("**Reviewed ").append(result.filesInScope())
                .append(result.filesInScope() == 1 ? " file**" : " files**");
        if (result.findings().isEmpty()) {
            return out.append(" · no issues found ✅").toString();
        }
        for (Severity severity : Severity.values()) {
            long count = result.count(severity);
            if (count > 0) {
                out.append(" · ").append(severity.emoji()).append(' ').append(count).append(' ')
                        .append(severity.slug()).append(severity == Severity.SUGGESTION && count > 1 ? "s" : "");
            }
        }
        return out.toString();
    }

    public String explainReply(String explanation) {
        return explanation.strip() + "\n\n<sub>Generated by ReviewBot; double-check before acting on it.</sub>";
    }

    public String fixReply(FixProposal proposal, boolean inThread, FindingContext finding) {
        String explanation = proposal.explanation() == null ? "" : proposal.explanation().strip();
        String replacement = proposal.replacement() == null ? "" : proposal.replacement().replace("\r\n", "\n").stripTrailing();
        if (!proposal.canFix() || replacement.isBlank() || SecretRedactor.containsRedaction(replacement)) {
            return "I can't offer a safe one-click fix for this finding.\n\n" + explanation;
        }
        StringBuilder out = new StringBuilder(explanation).append("\n\n");
        if (inThread) {
            out.append(fence("suggestion", replacement));
        } else {
            String lines = finding.startLine() != null ? "lines " + finding.startLine() + "–" + finding.line() : "line " + finding.line();
            out.append("Replace ").append(lines).append(" of `").append(finding.file()).append("` with:\n\n")
                    .append(fence(codeLanguage(finding.file()), replacement));
        }
        return out.append("\n\n<sub>Generated by ReviewBot; review the change before committing it.</sub>").toString();
    }

    public String commandHelp() {
        return "I couldn't tell which finding you mean. Reply to one of my inline comments with `" + mention
                + " explain` or `" + mention + " fix`, or comment `" + mention
                + " explain 3` here using a number from the review summary.";
    }

    public String commandDeclined() {
        return "I couldn't produce an answer for this finding. Try rephrasing the question, or check the finding manually.";
    }

    public String budgetNotice() {
        return "⏸️ **ReviewBot skipped this pull request:** the daily review budget for this installation has been "
                + "reached. Reviews resume automatically after midnight UTC; push a new commit then to get one.";
    }

    private String location(String repo, int prNumber, ReviewFinding finding) {
        String label = shortPath(finding.file()) + ":" + finding.line();
        String anchor = "diff-" + sha256(finding.file()) + "R" + finding.line();
        return "[`" + label + "`](" + webUrl + "/" + repo + "/pull/" + prNumber + "/files#" + anchor + ")";
    }

    private static String shortPath(String path) {
        if (path.length() <= 48) {
            return path;
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : "…" + path.substring(slash);
    }

    /** A fenced code block whose fence is longer than any backtick run inside the code. */
    static String fence(String info, String code) {
        int longest = 2;
        Matcher matcher = BACKTICK_RUN.matcher(code);
        while (matcher.find()) {
            longest = Math.max(longest, matcher.group().length());
        }
        String ticks = "`".repeat(longest + 1);
        return ticks + info + "\n" + code + "\n" + ticks;
    }

    private static String codeLanguage(String path) {
        String language = Languages.of(path);
        return "text".equals(language) ? "" : language;
    }

    private static String tableCell(String text) {
        return singleLine(text).replace("|", "\\|");
    }

    private static String singleLine(String text) {
        return text == null ? "" : text.replaceAll("\\s*\\R\\s*", " ").strip();
    }

    private static String truncateBody(String body, String marker) {
        if (body.length() <= MAX_BODY) {
            return body;
        }
        return body.substring(0, MAX_BODY - marker.length() - 64) + "\n\n…(truncated)\n" + marker;
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
