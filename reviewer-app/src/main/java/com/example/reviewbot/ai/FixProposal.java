package com.example.reviewbot.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("A minimal fix for one code review finding")
public record FixProposal(
        @JsonPropertyDescription("false if the problem cannot be fixed by replacing only the target lines")
        boolean canFix,
        @JsonPropertyDescription("Two or three sentences explaining the fix, or why a local fix is not possible")
        String explanation,
        @JsonPropertyDescription("The complete new text of the target lines, keeping their original indentation; "
                + "an empty string when canFix is false")
        String replacement) {
}
