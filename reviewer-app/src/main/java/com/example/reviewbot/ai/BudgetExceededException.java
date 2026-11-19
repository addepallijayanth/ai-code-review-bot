package com.example.reviewbot.ai;

public class BudgetExceededException extends AiException {

    public BudgetExceededException(String message) {
        super(message, false, null);
    }
}
