package com.example.reviewbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.ai.FindingContext;
import com.example.reviewbot.ai.FixProposal;
import com.example.reviewbot.config.GitHubAppProperties;
import com.example.reviewbot.github.CommentAnchor;
import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewCommentFormatterTest {

    private final ReviewCommentFormatter formatter = new ReviewCommentFormatter(new GitHubAppProperties("1", "reviewbot",
            null, null, "secret", URI.create("https://api.github.com"), "https://github.com", "@reviewbot"));

    @Test
    void inlineCommentOffersAOneClickSuggestionForAnExactAnchor() {
        NumberedFinding numbered = new NumberedFinding(1, finding("String sql = \"... WHERE name = ?\";"),
                new CommentAnchor("src/UserDao.java", 13, null, true), "fp");

        String body = formatter.inlineComment(42, numbered);

        assertThat(body).startsWith("🔴 **Critical** · Security\n\n**SQL built from request input**");
        assertThat(body).contains("```suggestion\nString sql = \"... WHERE name = ?\";\n```");
        assertThat(body).contains("#1 · Reply with `@reviewbot explain` or `@reviewbot fix`");
        assertThat(body).endsWith("<!-- reviewbot:finding:42:1 -->");
    }

    @Test
    void inexactAnchorsGetAPlainCodeBlockInstead() {
        NumberedFinding numbered = new NumberedFinding(2, finding("fixed();"), new CommentAnchor("src/UserDao.java", 14, null, false), "fp");

        String body = formatter.inlineComment(42, numbered);

        assertThat(body).doesNotContain("```suggestion").contains("Possible fix:\n\n```java\nfixed();\n```");
    }

    @Test
    void fencesAreLongerThanAnyBacktickRunInTheCode() {
        assertThat(ReviewCommentFormatter.fence("md", "```js\ncode\n```")).startsWith("````md\n").endsWith("\n````");
    }

    @Test
    void summaryCountsFindingsAndListsSkippedFiles() {
        ReviewResult result = new ReviewResult(
                List.of(new NumberedFinding(1, finding(null), new CommentAnchor("src/UserDao.java", 13, null, true), "a"),
                        new NumberedFinding(2, minor("src/Service.java", 80), null, "b")),
                List.of(new FileFilter.SkippedFile("package-lock.json", FileFilter.SkipReason.LOCKFILE)),
                3, 2, 0, 0, List.of("`src/Big.java`: Stopped after 8 model calls."), List.of(), false);
        RepoConfig config = new RepoConfig(Severity.MINOR, List.of(), java.util.Set.of(), java.util.Map.of(), 15,
                List.of("Unknown setting `auto_approve` was ignored."));

        String body = formatter.summary(7, "acme/shop", 12, result, config, null, true);

        assertThat(body).contains("**Reviewed 3 files** · 🔴 1 critical · 🟡 1 minor");
        assertThat(body).contains("| 1 | 🔴 Critical | [`src/UserDao.java:13`](https://github.com/acme/shop/pull/12/files#diff-");
        assertThat(body).contains("1 finding without an inline comment").contains("`src/Service.java:80`");
        assertThat(body).contains("Skipped 1 file").contains("- `package-lock.json` — lockfile");
        assertThat(body).contains("Stopped after 8 model calls").contains("`.reviewbot.yml`: Unknown setting");
        assertThat(body).endsWith("<!-- reviewbot:review:7 -->");
    }

    @Test
    void headlineSaysWhenNothingWasFound() {
        ReviewResult clean = new ReviewResult(List.of(), List.of(), 1, 0, 0, 0, List.of(), List.of(), false);
        assertThat(formatter.headline(clean)).isEqualTo("**Reviewed 1 file** · no issues found ✅");
    }

    @Test
    void fixReplyIsASuggestionInAThreadAndACodeBlockElsewhere() {
        FindingContext context = new FindingContext("acme/shop", "src/UserDao.java", 15, 13, Severity.CRITICAL,
                Category.SECURITY, "t", "m", null, "", "", "q");
        FixProposal proposal = new FixProposal(true, "Bind the value as a parameter.", "    s.setString(1, name);\n");

        assertThat(formatter.fixReply(proposal, true, context)).contains("```suggestion\n    s.setString(1, name);\n```");
        assertThat(formatter.fixReply(proposal, false, context))
                .contains("Replace lines 13–15 of `src/UserDao.java` with:").contains("```java");
        assertThat(formatter.fixReply(new FixProposal(false, "Needs a new import.", ""), true, context))
                .startsWith("I can't offer a safe one-click fix");
    }

    private static ReviewFinding finding(String suggestion) {
        return new ReviewFinding("src/UserDao.java", 13, null, Severity.CRITICAL, Category.SECURITY,
                "SQL built from request input", "`name` is concatenated into the query.", suggestion, "",
                ReviewFinding.Source.AGENT);
    }

    private static ReviewFinding minor(String file, int line) {
        return new ReviewFinding(file, line, null, Severity.MINOR, Category.ERROR_HANDLING, "Exception swallowed",
                "The catch block hides the failure.", null, "", ReviewFinding.Source.AGENT);
    }
}
