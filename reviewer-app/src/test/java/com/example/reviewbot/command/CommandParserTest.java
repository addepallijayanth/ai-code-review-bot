package com.example.reviewbot.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.config.GitHubAppProperties;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandParserTest {

    private final CommandParser parser = new CommandParser(new GitHubAppProperties("1", "acme-reviewbot", null, null,
            "secret", URI.create("https://api.github.com"), "https://github.com", "@reviewbot"));

    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource(delimiter = '|', value = {
            "@reviewbot explain                         | EXPLAIN |",
            "@ReviewBot FIX                             | FIX     |",
            "Thanks! @reviewbot explain why this leaks? | EXPLAIN |",
            "@reviewbot explain 3                       | EXPLAIN | 3",
            "@reviewbot fix #12 please                  | FIX     | 12",
            "@acme-reviewbot explain                    | EXPLAIN |",
            "line one\\n@reviewbot fix                  | FIX     |"
    })
    void recognisesCommands(String body, CommandType type, Integer number) {
        assertThat(parser.parse(body.replace("\\n", "\n"))).hasValueSatisfying(command -> {
            assertThat(command.type()).isEqualTo(type);
            assertThat(command.findingNumber()).isEqualTo(number);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Reply with `@reviewbot explain` or `@reviewbot fix`",
            "@reviewbot please explain",
            "@reviewbotexplain",
            "email me at dev@reviewbot explain",
            "",
            "   "
    })
    void ignoresEverythingElse(String body) {
        assertThat(parser.parse(body)).isEmpty();
    }
}
