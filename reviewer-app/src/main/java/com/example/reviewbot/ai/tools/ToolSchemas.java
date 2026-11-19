package com.example.reviewbot.ai.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds strict tool definitions. Properties keep their declaration order so the serialized tool
 * list is byte-identical on every request, which the prompt cache depends on.
 */
final class ToolSchemas {

    record Property(String name, Map<String, Object> schema) {
    }

    private ToolSchemas() {
    }

    static Property string(String name, String description) {
        return new Property(name, schema("string", description));
    }

    static Property integer(String name, String description) {
        return new Property(name, schema("integer", description));
    }

    static Property oneOf(String name, String description, List<String> values) {
        Map<String, Object> schema = schema("string", description);
        schema.put("enum", values);
        return new Property(name, schema);
    }

    static BetaTool strictTool(String name, String description, List<String> required, Property... properties) {
        BetaTool.InputSchema.Properties.Builder props = BetaTool.InputSchema.Properties.builder();
        for (Property property : properties) {
            props.putAdditionalProperty(property.name(), JsonValue.from(property.schema()));
        }
        BetaTool.InputSchema schema = BetaTool.InputSchema.builder()
                .properties(props.build())
                .required(required)
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
        return BetaTool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .strict(true)
                .build();
    }

    /** Normalises a repository-relative path, or returns null if it tries to leave the repository. */
    static String normalizePath(String raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.strip().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || path.contains("//")) {
            return null;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return null;
            }
        }
        return path;
    }

    private static Map<String, Object> schema(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("description", description);
        return schema;
    }
}
