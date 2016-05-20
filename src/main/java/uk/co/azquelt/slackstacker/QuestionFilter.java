package uk.co.azquelt.slackstacker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

public class QuestionFilter {

    public static List<String> collectTags(JsonNode filterNode) {
        Set<String> tags = new HashSet<String>();
        collectTags(tags, filterNode);
        return new ArrayList<String>(tags);
    }

    private static void collectTags(Set<String> tags, JsonNode filterNode) {
        if (!filterNode.isObject()) {
            throw new IllegalArgumentException("Must be an object");
        }
        if (filterNode.size() != 1) {
            throw new IllegalArgumentException("Must have one 'or' or 'and' node");
        }
        JsonNode andNode = filterNode.get("and");
        if (andNode == null) {
            JsonNode orNode = filterNode.get("or");
            if (orNode == null) {
                throw new IllegalArgumentException("'or' or 'and' node is required");
            } else {
                collectConditionTags(tags, orNode);
            }
        } else {
            collectConditionTags(tags, andNode);
        }
    }

    private static void collectConditionTags(Set<String> tags, JsonNode filterNode) {
        if (!filterNode.isArray()) {
            throw new IllegalArgumentException("Value of 'and' must be an array");
        }
        for (JsonNode item : filterNode) {
            if (item.isTextual()) {
                tags.add(item.textValue());
            } else if (item.isObject()) {
                collectTags(tags, item);
            } else {
                throw new IllegalArgumentException("Unsupported type: " + item);
            }
        }
    }

    public static boolean evaluate(List<String> tags, JsonNode filterNode) {
        if (!filterNode.isObject()) {
            throw new IllegalArgumentException("Must be an object");
        }
        if (filterNode.size() != 1) {
            throw new IllegalArgumentException("Must have one 'or' or 'and' node");
        }
        JsonNode andNode = filterNode.get("and");
        if (andNode == null) {
            JsonNode orNode = filterNode.get("or");
            if (orNode == null) {
                throw new IllegalArgumentException("'or' or 'and' node is required");
            } else {
                return evaluateOr(tags, orNode);
            }
        } else {
            return evaluateAnd(tags, andNode);
        }
    }

    private static boolean evaluateOr(List<String> tags, JsonNode filterNode) {
        if (!filterNode.isArray()) {
            throw new IllegalArgumentException("Value of 'or' must be an array");
        }
        for (JsonNode item : filterNode) {
            if (item.isTextual()) {
                if (tags.contains(item.textValue())) {
                    return true;
                }
            } else if (item.isObject()) {
                if (evaluate(tags, item)) {
                    return true;
                }
            } else {
                throw new IllegalArgumentException("Unsupported type: " + item);
            }
        }
        return false;
    }

    private static boolean evaluateAnd(List<String> tags, JsonNode filterNode) {
        if (!filterNode.isArray()) {
            throw new IllegalArgumentException("Value of 'and' must be an array");
        }
        for (JsonNode item : filterNode) {
            if (item.isTextual()) {
                if (!tags.contains(item.textValue())) {
                    return false;
                }
            } else if (item.isObject()) {
                if (!evaluate(tags, item)) {
                    return false;
                }
            } else {
                throw new IllegalArgumentException("Unsupported type: " + item);
            }
        }
        return true;
    }

}
