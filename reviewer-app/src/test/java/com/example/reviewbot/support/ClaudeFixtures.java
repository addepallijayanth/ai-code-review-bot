package com.example.reviewbot.support;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Map;

/** Messages API JSON for scripted model responses, parsed with the SDK's own mapper. */
public final class ClaudeFixtures {

    private ClaudeFixtures() {
    }

    public static String toolUseJson(String model, String... toolUseBlocks) {
        return message(model, "tool_use", String.join(",", toolUseBlocks));
    }

    public static String textJson(String model, String text) {
        return message(model, "end_turn", "{\"type\":\"text\",\"text\":" + quote(text) + "}");
    }

    public static String refusalJson(String model) {
        return """
                {"id":"msg_refused","type":"message","role":"assistant","model":"%s","content":[],
                 "stop_reason":"refusal","stop_sequence":null,"stop_details":{"type":"refusal","category":"cyber"},
                 "usage":{"input_tokens":900,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
                """.formatted(model);
    }

    public static String toolUse(String id, String name, Map<String, Object> input) {
        return "{\"type\":\"tool_use\",\"id\":" + quote(id) + ",\"name\":" + quote(name) + ",\"input\":" + json(input) + "}";
    }

    public static BetaMessage parseMessage(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, BetaMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static BetaToolUseBlock toolUseBlock(String name, Map<String, Object> input) {
        try {
            return ObjectMappers.jsonMapper().readValue(toolUse("toolu_test", name, input), BetaToolUseBlock.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String message(String model, String stopReason, String content) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"%s","content":[%s],
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":1200,"output_tokens":240,"cache_creation_input_tokens":2600,"cache_read_input_tokens":0}}
                """.formatted(model, content, stopReason);
    }

    /** Wire JSON of an SDK object, e.g. a message param, as it would be sent to the API. */
    public static String toJson(Object value) {
        return json(value);
    }

    private static String json(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String quote(String text) {
        return json(text);
    }
}
