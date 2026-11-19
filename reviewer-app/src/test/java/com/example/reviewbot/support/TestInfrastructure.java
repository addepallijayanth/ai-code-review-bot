package com.example.reviewbot.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import redis.embedded.RedisServer;

/**
 * PostgreSQL and Redis for integration tests, started once per JVM. Uses Testcontainers when
 * Docker is available (CI), and falls back to an embedded PostgreSQL and a bundled Redis binary on
 * machines without Docker. Set {@code REVIEWBOT_TEST_INFRA=embedded} to force the fallback.
 * <p>
 * Where even the bundled Redis binary cannot run (for example under an application-control
 * policy), the queue is replaced by {@link InMemoryQueueConfig}; everything else stays real.
 */
public final class TestInfrastructure {

    private static final Logger log = LoggerFactory.getLogger(TestInfrastructure.class);
    private static TestInfrastructure instance;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String redisHost;
    private final int redisPort;

    private TestInfrastructure(String jdbcUrl, String username, String password, String redisHost, int redisPort) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
    }

    /** True when a real Redis is running for the tests. */
    public boolean hasRedis() {
        return redisPort > 0;
    }

    public static synchronized TestInfrastructure get() {
        if (instance == null) {
            instance = useDocker() ? startContainers() : startEmbedded();
        }
        return instance;
    }

    public void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        if (hasRedis()) {
            registry.add("spring.data.redis.host", () -> redisHost);
            registry.add("spring.data.redis.port", () -> redisPort);
        } else {
            registry.add("reviewbot.test.in-memory-queue", () -> "true");
        }
    }

    private static boolean useDocker() {
        if ("embedded".equalsIgnoreCase(System.getenv("REVIEWBOT_TEST_INFRA"))) {
            return false;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static TestInfrastructure startContainers() {
        log.info("Starting PostgreSQL and Redis with Testcontainers");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
        GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
        postgres.start();
        redis.start();
        return new TestInfrastructure(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                redis.getHost(), redis.getMappedPort(6379));
    }

    private static TestInfrastructure startEmbedded() {
        log.info("Docker is not available; starting embedded PostgreSQL and Redis");
        EmbeddedPostgres postgres;
        try {
            postgres = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (Exception ignored) {
                // best effort at JVM exit
            }
        }));
        int redisPort = startEmbeddedRedis();
        return new TestInfrastructure(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres",
                "127.0.0.1", redisPort);
    }

    /** Returns the port of a bundled Redis binary, or 0 when it cannot run on this machine. */
    private static int startEmbeddedRedis() {
        try {
            int port = freePort();
            RedisServer redis = RedisServer.newRedisServer().port(port).setting("bind 127.0.0.1").build();
            redis.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    redis.stop();
                } catch (Exception ignored) {
                    // best effort at JVM exit
                }
            }));
            return port;
        } catch (IOException | RuntimeException e) {
            log.warn("Embedded Redis could not start ({}); integration tests use an in-memory queue instead", e.getMessage());
            return 0;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
