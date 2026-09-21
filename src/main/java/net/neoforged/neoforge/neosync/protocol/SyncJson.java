/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class SyncJson {
    private SyncJson() {}

    public static JsonElement parse(byte[] bytes, int limit) throws IOException {
        if (bytes.length > limit) throw new IOException("The JSON response exceeds the size limit.");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("The JSON response is not valid UTF-8.", e);
        }
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            var result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Unexpected data after JSON.");
            return result;
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Invalid JSON response.", e);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw new IOException("JSON nesting exceeds the limit.");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var result = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (result.has(key)) throw new IOException("Duplicate JSON field.");
                    result.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                var result = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) result.add(read(reader, depth + 1));
                reader.endArray();
                yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 32) throw new IOException("JSON number exceeds the limit.");
                yield new JsonPrimitive(new BigDecimal(number));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Unexpected JSON token.");
        };
    }

    public static JsonObject object(JsonElement value, Set<String> required, Set<String> optional) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException("Expected a JSON object.");
        var object = value.getAsJsonObject();
        var allowed = new HashSet<>(required);
        allowed.addAll(optional);
        if (!object.keySet().containsAll(required) || !allowed.containsAll(object.keySet())) {
            throw new IOException("Missing or unsupported JSON fields.");
        }
        return object;
    }

    public static JsonArray array(JsonElement value, int min, int max) throws IOException {
        if (value == null || !value.isJsonArray()) throw new IOException("Expected a JSON array.");
        var array = value.getAsJsonArray();
        if (array.size() < min || array.size() > max) throw new IOException("JSON array exceeds the allowed bounds.");
        return array;
    }

    public static String string(JsonElement value, int max) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Expected a JSON string.");
        }
        String text = value.getAsString();
        if (text.isEmpty() || text.length() > max || text.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE || c == 0xA7)) {
            throw new IOException("Invalid text in JSON.");
        }
        return text;
    }

    public static long number(JsonElement value, long min, long max) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Expected a JSON integer.");
        }
        try {
            long number = value.getAsBigDecimal().longValueExact();
            if (number < min || number > max) throw new IOException("JSON integer is out of bounds.");
            return number;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IOException("Invalid JSON integer.", e);
        }
    }

    public static boolean bool(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Expected a JSON boolean.");
        }
        return value.getAsBoolean();
    }

    public static String matching(JsonElement value, int max, String pattern) throws IOException {
        String result = string(value, max);
        if (!result.matches(pattern)) throw new IOException("Invalid JSON identifier.");
        return result;
    }
}
