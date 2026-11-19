package com.example.reviewbot.support;

import com.example.reviewbot.config.AiProperties;
import com.example.reviewbot.config.ReviewProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The real defaults from {@code application.yml}, bound without starting a Spring context. */
public final class TestProperties {

    private static final Binder BINDER = binder();

    private TestProperties() {
    }

    public static AiProperties ai() {
        return BINDER.bind("reviewbot.ai", AiProperties.class).get();
    }

    public static ReviewProperties review() {
        return BINDER.bind("reviewbot.review", ReviewProperties.class).get();
    }

    private static Binder binder() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                    .forEach(source -> environment.getPropertySources().addLast(source));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Binder.get(environment);
    }
}
