package com.acme.marketing;

public class CampaignMetrics {

    private final CampaignStatsRepository statsRepository;

    public CampaignMetrics(CampaignStatsRepository statsRepository) {
        this.statsRepository = statsRepository;
    }

    /** Percentage of visits that ended in a purchase, e.g. 2.5 for 2.5%. */
    public double conversionRate(long campaignId) {
        CampaignStats stats = statsRepository.load(campaignId);
        if (stats.visits() == 0) {
            return 0.0;
        }
        return stats.purchases() / stats.visits() * 100.0;
    }
}
