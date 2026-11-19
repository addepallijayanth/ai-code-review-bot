package com.example.reviewbot.github.dto;

public record GitHubUser(String login, String type) {

    public boolean isBot() {
        return "Bot".equalsIgnoreCase(type) || (login != null && login.endsWith("[bot]"));
    }
}
