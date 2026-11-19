package com.example.reviewbot.command;

public enum CommandType {
    /** Explain the finding in more depth. */
    EXPLAIN,
    /** Propose a concrete fix, as a one-click suggestion when replying in the finding's thread. */
    FIX
}
