package com.example.reviewbot.eval;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.example.reviewbot.ai.ClaudeClient;
import com.example.reviewbot.ai.ModelPricing;
import com.example.reviewbot.ai.ModelRouter;
import com.example.reviewbot.ai.PromptLibrary;
import com.example.reviewbot.ai.TriageService;
import com.example.reviewbot.ai.agent.ReviewAgent;
import com.example.reviewbot.ai.tools.GetRelatedTestsTool;
import com.example.reviewbot.ai.tools.ReadFileTool;
import com.example.reviewbot.ai.tools.ReportFindingTool;
import com.example.reviewbot.ai.tools.SearchCodeTool;
import com.example.reviewbot.config.AiProperties;
import com.example.reviewbot.config.ReviewProperties;
import com.example.reviewbot.github.DiffParser;
import com.example.reviewbot.github.PositionMapper;
import com.example.reviewbot.review.Chunker;
import com.example.reviewbot.review.FileFilter;
import com.example.reviewbot.review.FindingDeduplicator;
import com.example.reviewbot.review.ReviewPipeline;
import com.example.reviewbot.review.SecretRedactor;
import com.example.reviewbot.review.SeverityPolicy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Builds the production review pipeline without the web app, database or queue: same prompts,
 * models, limits and prices, read from the reviewer app's {@code application.yml} (environment
 * variables still override it, e.g. {@code ANTHROPIC_API_KEY}).
 */
final class PipelineFactory implements AutoCloseable {

    private final AiProperties ai;
    private final ReviewProperties review;
    private final AnthropicClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ReviewPipeline pipeline;

    PipelineFactory() {
        Binder binder = binder();
        this.ai = binder.bind("reviewbot.ai", AiProperties.class).get();
        this.review = binder.bind("reviewbot.review", ReviewProperties.class).get();
        if (ai.apiKey() == null || ai.apiKey().isBlank()) {
            throw new IllegalStateException("Set ANTHROPIC_API_KEY to run the evaluation");
        }
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(ai.apiKey())
                .baseUrl(ai.baseUrl())
                .maxRetries(ai.maxRetries())
                .timeout(ai.requestTimeout())
                .build();
        ClaudeClient claude = new ClaudeClient(client, new ModelRouter(ai), new ModelPricing(ai));
        PromptLibrary prompts = new PromptLibrary();
        ReviewAgent agent = new ReviewAgent(claude, prompts, ai, new ReadFileTool(), new SearchCodeTool(),
                new GetRelatedTestsTool(), new ReportFindingTool());
        this.pipeline = new ReviewPipeline(new FileFilter(review), new DiffParser(), new SecretRedactor(),
                new Chunker(review), new TriageService(claude, prompts, ai), agent, new FindingDeduplicator(),
                new SeverityPolicy(), new PositionMapper(), review, ai, executor);
    }

    ReviewPipeline pipeline() {
        return pipeline;
    }

    AiProperties ai() {
        return ai;
    }

    ReviewProperties review() {
        return review;
    }

    @Override
    public void close() {
        executor.close();
        client.close();
    }

    private static Binder binder() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                    .forEach(source -> environment.getPropertySources().addLast(source));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read application.yml from the reviewer app", e);
        }
        return Binder.get(environment);
    }
}
