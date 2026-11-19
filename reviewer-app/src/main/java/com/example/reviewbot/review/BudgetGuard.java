package com.example.reviewbot.review;

import com.example.reviewbot.ai.TokenBudget;
import com.example.reviewbot.config.ReviewProperties;
import com.example.reviewbot.repository.UsageRecordRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Spend limits per pull request, per chat command, and per installation per UTC day. */
@Component
public class BudgetGuard {

    private final ReviewProperties.Budget budget;
    private final UsageRecordRepository usage;
    private final Clock clock;

    public BudgetGuard(ReviewProperties properties, UsageRecordRepository usage, Clock clock) {
        this.budget = properties.budget();
        this.usage = usage;
        this.clock = clock;
    }

    public TokenBudget forPullRequest() {
        return new TokenBudget(budget.maxTokensPerPr(), budget.maxCostPerPrUsd());
    }

    public TokenBudget forCommand() {
        return new TokenBudget(budget.maxTokensPerCommand(), budget.maxCostPerCommandUsd());
    }

    public boolean dailyLimitReached(long installationRowId) {
        BigDecimal spent = usage.sumCostForInstallationSince(installationRowId, startOfDay());
        return spent != null && spent.compareTo(budget.maxCostPerInstallationPerDayUsd()) >= 0;
    }

    public Instant startOfDay() {
        return clock.instant().truncatedTo(ChronoUnit.DAYS);
    }
}
