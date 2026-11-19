package com.example.reviewbot.ai;

/** Why a model call was made; drives model routing and shows up in usage records. */
public enum Purpose {
    TRIAGE,
    REVIEW,
    EXPLAIN,
    FIX
}
