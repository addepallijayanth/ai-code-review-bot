package com.example.reviewbot.command;

import com.example.reviewbot.config.GitHubAppProperties;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Recognises {@code @reviewbot explain}, {@code @reviewbot fix} and the numbered forms
 * ({@code @reviewbot explain 3}, {@code @reviewbot fix #3}). The App's own {@code @slug} works too.
 * A mention inside backticks (as in the bot's own footer) is not a command.
 */
@Component
public class CommandParser {

    public record ParsedCommand(CommandType type, Integer findingNumber) {
    }

    private final Pattern pattern;

    public CommandParser(GitHubAppProperties properties) {
        Set<String> handles = new LinkedHashSet<>();
        handles.add(withAt(properties.mention()));
        handles.add(withAt(properties.appSlug()));
        String alternatives = handles.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        this.pattern = Pattern.compile("(?:^|\\s)(?:" + alternatives + ")\\s+(explain|fix)\\b(?:\\s+#?(\\d{1,4})\\b)?",
                Pattern.CASE_INSENSITIVE);
    }

    public Optional<ParsedCommand> parse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return Optional.empty();
        }
        CommandType type = "fix".equalsIgnoreCase(matcher.group(1)) ? CommandType.FIX : CommandType.EXPLAIN;
        Integer number = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
        return Optional.of(new ParsedCommand(type, number));
    }

    private static String withAt(String handle) {
        String trimmed = handle.trim();
        return trimmed.startsWith("@") ? trimmed : "@" + trimmed;
    }
}
