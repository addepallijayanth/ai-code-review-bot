package com.example.reviewbot.github.dto;

import java.util.List;

public record CodeSearchHit(String path, List<String> fragments) {
}
