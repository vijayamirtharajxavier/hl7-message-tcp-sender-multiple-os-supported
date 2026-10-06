package io.hl7sender.core.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.TreeMap;

/** Stores a destination's transport settings as a JSON object of strings. */
final class TransportOptionsJson {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<TreeMap<String, String>> TYPE = new TypeReference<>() {
    };

    private TransportOptionsJson() {
    }

    static String write(Map<String, String> options) {
        try {
            return JSON.writeValueAsString(new TreeMap<>(options));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> read(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, TYPE);
        } catch (JsonProcessingException e) {
            throw new QueueException("Unreadable transport settings: " + e.getOriginalMessage(), e);
        }
    }
}
