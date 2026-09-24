package utils;

import com.fasterxml.jackson.databind.JsonNode;
import io.mangoo.utils.CommonUtils;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

public final class DbUtils {
    public static Object toMongoValue(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }

        if (value.isBoolean()) {
            return value.asBoolean();
        }

        if (value.isInt()) {
            return value.asInt();
        }

        if (value.isLong()) {
            return value.asLong();
        }

        if (value.isFloatingPointNumber()) {
            return value.asDouble();
        }

        if (value.isNull()) {
            return null;
        }

        if (value.isObject()) {
            return Document.parse(value.toString());
        }

        if (value.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonNode item : value) {
                items.add(toMongoValue(item));
            }
            return items;
        }

        throw new IllegalArgumentException("Unsupported JSON type: " + value.getNodeType());
    }

    public static String id() {
        return CommonUtils.uuidV7();
    }
}
