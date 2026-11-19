package com.example.reviewbot.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** Structured output schema for triage; the SDK derives the JSON schema from these records. */
@JsonClassDescription("Triage decisions for the files of a pull request")
public record TriageOutput(
        @JsonPropertyDescription("One entry per file in the request, in the same order")
        List<FileTriage> files) {

    @JsonClassDescription("Triage decision for one file")
    public record FileTriage(
            @JsonPropertyDescription("The file path exactly as given in the request")
            String path,
            @JsonPropertyDescription("true if the change could plausibly contain a bug, a security problem, or untested "
                    + "logic that a careful reviewer should look at line by line")
            boolean needsDeepReview,
            @JsonPropertyDescription("Risk of the change: low, medium, or high")
            String risk,
            @JsonPropertyDescription("One short sentence explaining the decision")
            String reason) {
    }
}
