/*
 * Copyright 2016 Sony Mobile Communications, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package lumbermill.api;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okio.ByteString;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Represents a Json Object(!)
 *
 * @see AnyJsonEvent for handling Json arrays
 */
public class JsonEvent extends MetaDataEvent {

    protected static ObjectMapper objectMapper = new ObjectMapper();

    protected final ObjectNode jsonNode;

    public JsonEvent() {
        this.jsonNode = objectMapper.createObjectNode();
    }


    public ObjectNode copyNode() {
        return jsonNode.deepCopy();
    }

    public JsonEvent(ObjectNode jsonNode) {
        this.jsonNode = jsonNode;
    }

    public JsonEvent put(String fieldName, String value) {
        jsonNode.put(fieldName, value);
        return this;
    }

    public JsonEvent put(String fieldName, int value) {
        jsonNode.put(fieldName, value);
        return this;
    }

    public JsonEvent put(String fieldName, long value) {
        jsonNode.put(fieldName, value);
        return this;
    }

    public JsonEvent put(String fieldName, float value) {
        jsonNode.put(fieldName, value);
        return this;
    }

    public JsonEvent put(String fieldName, boolean value) {
        jsonNode.put(fieldName, value);
        return this;
    }

    public Boolean asBoolean(String field) {
        return jsonNode.get(field).asBoolean();
    }

    public void putMetaData(String key, Object value) {
        super.put(key, value);
    }

    @Override
    public String valueAsString(String field) {
        if (this.jsonNode.has(field)) {

            JsonNode node = jsonNode.get(field);

            // TODO: This should find another home
            // Support for boolean expressions of arrays
            if (node instanceof ArrayNode) {
                Iterator<JsonNode> elements = node.elements();
                StringBuffer sb = new StringBuffer("[");
                while (elements.hasNext()) {
                    sb.append("'").append(elements.next().asText()).append("'");
                    if (elements.hasNext()) {
                        sb.append(",");
                    }
                }
                return sb.append("]").toString();
            }

            if (node == null) {
                return null;
            }

            return node.asText();
        }
        return super.valueAsString(field);
    }

    public boolean has(String field) {
        return jsonNode.has(field) ? true : super.has(field);
    }

    @Override
    public String toString() {
        return toString(true);
    }

    public String toString(boolean pretty)
    {
        try {
            return pretty ? objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode)
                    : objectMapper.writeValueAsString(jsonNode);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void merge(JsonEvent event) {
        Iterator<String> stringIterator = event.jsonNode.fieldNames();
        while(stringIterator.hasNext()) {
            String field = stringIterator.next();
            jsonNode.set(field, event.jsonNode.get(field));
        }
    }

    public void eachField(FieldProcessor processor) {

        ArrayList<String> list = new ArrayList<String >();
        Iterator<String> stringIterator = jsonNode.fieldNames();
        while(stringIterator.hasNext()) {
            list.add(stringIterator.next());
        }

        for(String field : list) {
            processor.process(field, jsonNode.get(field).asText());
        }
    }

    @Override
    public ByteString raw() {
        return ByteString.encodeUtf8(toString(false));
    }

    public JsonEvent remove(String... fields) {
        for(String field : fields) {
            jsonNode.remove(field);
        }
        return this;
    }

    public boolean contains(String field, String value) {
        if (!jsonNode.has(field)) {
            return false;
        }
        JsonNode jsonNode = this.jsonNode.get(field);
        if (jsonNode instanceof ArrayNode) {
            for (JsonNode  node : jsonNode) {
                if (node.asText().equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    public JsonEvent add(String field, String... values) {
        for (String value : values) {
            if (jsonNode.has(field)) {
                ArrayNode arrayNode = (ArrayNode) this.jsonNode.get(field);
                arrayNode.add(value);
            } else {
                jsonNode.set(field, jsonNode.arrayNode().add(value));
            }
        }
        return this;
    }

    @Override
    public int hashCode() {
        return jsonNode.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        JsonEvent jsonEvent = (JsonEvent) o;
        return jsonNode != null ? jsonNode.equals(jsonEvent.jsonNode) : jsonEvent.jsonNode == null;
    }

    @Override
    public void addTags(List<String> tags) {
        add("tags", tags.toArray(new String[0]));
    }

    @Override
    public Event addTag(String tag) {
        add("tags", tag);
        return this;
    }

    @Override
    public boolean hasTag(String tag) {
        return contains("tags", tag) ? true : super.hasTag(tag);
    }

    public void removeIfExists(String field) {
        if (has(field)) {
            remove(field);
        }
    }

    public JsonEvent add(Map<String, Object> fieldsAndValues) {
        for (String key : fieldsAndValues.keySet()) {
            putObject(key, fieldsAndValues.get(key));
        }
        return this;
    }

    private void putObject(String key, Object o) {
        if (o instanceof String) {
            jsonNode.put(key, (String) o);
        } else if (o instanceof Integer) {
            jsonNode.put(key, (Integer)o);
        } else if (o instanceof Boolean) {
            jsonNode.put(key, (Boolean)o);
        } else if (o instanceof Float) {
            jsonNode.put(key, (Float)o);
        } else {
            jsonNode.put(key, String.valueOf(o));
        }
    }

    public AnyJsonEvent child(String field) {
        return new AnyJsonEvent(this.jsonNode.get(field));
    }

    public JsonEvent objectChild(String field) {
        return new JsonEvent((ObjectNode) this.jsonNode.get(field));
    }


    public interface FieldProcessor {
        void process(String field, String value);
    }
}
