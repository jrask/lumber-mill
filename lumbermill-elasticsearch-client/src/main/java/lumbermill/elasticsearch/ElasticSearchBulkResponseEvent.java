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
package lumbermill.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lumbermill.api.JsonEvent;
import lumbermill.internal.Json;
import lumbermill.internal.elasticsearch.ElasticSearchBulkResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * <p>This result matches an Elasticsearch Bulk API response
 */
public class ElasticSearchBulkResponseEvent extends JsonEvent {

    private final Map<JsonEvent, JsonEvent> eventAndResponse = new HashMap<>();

    public static ElasticSearchBulkResponseEvent of(ElasticSearchBulkResponse bulkResponse) {
        return new ElasticSearchBulkResponseEvent(buildJsonResponse(bulkResponse.hasErrors(), bulkResponse.eventWithResponse),
                bulkResponse.eventWithResponse);
    }

    public static ElasticSearchBulkResponseEvent ofPostponed(List<JsonEvent> events) {
        return new ElasticSearchBulkResponseEvent(buildPostponedJsonResponse(events), new HashMap<>());
    }

    public static ElasticSearchBulkResponseEvent ofPostponed(ElasticSearchBulkRequestEvent requestEvent) {
        return new ElasticSearchBulkResponseEvent(buildPostponedJsonResponse(requestEvent), new HashMap<>());
    }



    private ElasticSearchBulkResponseEvent(ObjectNode node, Map<JsonEvent, JsonEvent> eventAndResponse) {
        super(node);
        this.eventAndResponse.putAll(eventAndResponse);
    }


    public ElasticSearchBulkResponseEvent nextAttempt(ElasticSearchBulkResponse elasticSearchBulkResponse) {
        eventAndResponse.putAll(elasticSearchBulkResponse.eventWithResponse);
        return new ElasticSearchBulkResponseEvent(buildJsonResponse(elasticSearchBulkResponse.hasErrors(), eventAndResponse),
                eventAndResponse);
    }


    private static ObjectNode buildJsonResponse(boolean hasErrors, Map<JsonEvent, JsonEvent> eventAndResponse) {
        ObjectNode node = Json.OBJECT_MAPPER.createObjectNode()
                .put("errors", hasErrors)
                .put("took", 1L);
        node.putArray("items")
                .addAll(
                        eventAndResponse.values()
                                .stream()
                                .map(jsonEvent -> jsonEvent.copyNode())
                                .collect(Collectors.toList()));
        return node;

    }


    private static ObjectNode buildPostponedJsonResponse(ElasticSearchBulkRequestEvent requestEvent) {
        ObjectNode node = Json.OBJECT_MAPPER.createObjectNode()
                .put("errors", false)
                .put("took", 1L);
        node.putArray("items")
                .addAll(
                        requestEvent.indexRequests().stream()
                                .map(tuple -> tuple.getSecond())
                                .map(ElasticSearchBulkResponseEvent::toPostponsedEvent)
                                .collect(Collectors.toList()));
        return node;
    }

    private static ObjectNode buildPostponedJsonResponse(List<JsonEvent> events) {
        ObjectNode node = Json.OBJECT_MAPPER.createObjectNode()
                .put("errors", false)
                .put("took", 1L);
        node.putArray("items")
                .addAll(
                        events.stream()
                                .map(ElasticSearchBulkResponseEvent::toPostponsedEvent)
                                .collect(Collectors.toList()));
        System.out.println("Response built: " + new JsonEvent(node).toString(true));
        return node;

    }

    private static JsonNode toPostponsedEvent(JsonEvent jsonEvent) {
        System.out.println("toPostPOned: " + jsonEvent.toString(true));
        ObjectNode objectNode = Json.OBJECT_MAPPER.createObjectNode();
        objectNode.putObject("create").put("_id", "ID_CREATION_POSTPONED").put("status",202);
        return objectNode;

        //{"create":{"_index":"logstash 2014.08.11.16","_type":"logs","_id":"HwBm8IAZT1ycsdJidIPlOQ","_version":1,"status":201}},
    }

}
