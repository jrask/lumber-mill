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
package lumbermill.internal.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.squareup.okhttp.Callback;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import groovy.lang.Tuple2;
import lumbermill.api.JsonEvent;
import lumbermill.elasticsearch.ElasticSearchBulkRequestEvent;
import lumbermill.elasticsearch.ElasticSearchBulkResponseEvent;
import lumbermill.elasticsearch.FatalIndexException;
import lumbermill.elasticsearch.IndexFailedException;
import lumbermill.internal.MapWrap;
import lumbermill.internal.StringTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.subjects.ReplaySubject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;


/**
 * Elasticsearch BULK API client based on OkHttpClient. This client attempts to be logstash compatible.
 *
 * Currently the bulk api support _/bulk only, not by index and type.
 *
 * With index prefix:
 * <pre><code>
 *    new ElasticSearchOkHttpClient("http://host:port", "lumbermill-", "atype", true);
 *</code></pre>
 * No index prefix:
 * <pre><code>
 *    new ElasticSearchOkHttpClient("http://host:port", "lumbermill", "atype", false);
 * </code></pre>
 *
 * It supports both "normal" Elasticsearch and AWS Elasticsearch identity based access control.
 * To use AWS, lumbermill-aws RequestSigner implementation must be used.
 *
 *
 */
@SuppressWarnings("unused")
public class ElasticSearchOkHttpClientImpl {

    public static final Logger LOGGER = LoggerFactory.getLogger(ElasticSearchOkHttpClientImpl.class);

    public static final MediaType TEXT
            = MediaType.parse("application/text; charset=utf-8");
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Http client is shared
     */
    private final static OkHttpClient client = new OkHttpClient();

    static {
        if (System.getenv("https_proxy") != null) {
            URI proxy = URI.create(System.getenv("https_proxy"));
            LOGGER.info("Using proxy {}", proxy);
           // client.setProxy (
           //         new Proxy(Proxy.Type.HTTP,
           //                 new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        client.setRetryOnConnectionFailure(true);
    }

    private final boolean indexIsPrefix;

    private String timestampField = "@timestamp";

    private final URL url;
    private final String index;
    private final StringTemplate type;

    private Optional<RequestSigner> signer = Optional.empty();

    public ElasticSearchOkHttpClientImpl(String esUrl, String index, String type, boolean isPrefix) {
        this.indexIsPrefix = isPrefix;
        try {
            this.url = new URL(esUrl + (esUrl.endsWith("/") ? "_bulk" : "/_bulk"));
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }

        this.index = index;
        this.type = StringTemplate.compile(type);
    }


    /**
     * Set AWS Signer if using AWS Elasticsearch as service with identity based access
     */
    public ElasticSearchOkHttpClientImpl withSigner(RequestSigner signer) {
        this.signer = Optional.of(signer);
        return this;
    }

    /**
     * Change timestamp field if you are not using @timestamp. The correct fieldname is currently
     * required in order to create correct timestamped indices.
     */
    public ElasticSearchOkHttpClientImpl withTimestampField(String field) {
        this.timestampField = field;
        return this;
    }


    public Observable<ElasticSearchBulkResponseEvent> postEvent(ElasticSearchBulkRequestEvent requestEvent) throws IndexFailedException {

        // TODO - Not sure how we really want this to work...
        List<JsonEvent> batch = requestEvent.indexRequests()
                .stream().map(jsonEventJsonEventTuple2 -> jsonEventJsonEventTuple2.getSecond()).collect(toList());
        LOGGER.debug("Sending batch of {} events", batch.size());
        RequestContext request = new RequestContext(batch, new ElasticSearchRequest(batch, url));
        post(request);
        return request.subject;


    }

    public Observable<ElasticSearchBulkResponseEvent> post(List<JsonEvent> batch) throws IndexFailedException {
        if (batch.isEmpty()) {
            LOGGER.info("Event batch is empty, skipping");
            Observable.just(batch);
        }
        LOGGER.debug("Sending batch of {} events", batch.size());
        RequestContext request = new RequestContext(batch, new ElasticSearchRequest(batch, url));
        post(request);
        return request.subject;


    }

    public void post(RequestContext request) throws IndexFailedException {

        if (signer.isPresent()) {
            LOGGER.trace("Found RequestSigner, signing request");
            signer.get().sign(request.signableRequest);
        }

        doOkHttpPost(request);
            /*
            handleResponse(request, response);

            if (LOGGER.isTraceEnabled()) {
                try {
                    LOGGER.trace("Successful elasticsearch response: {}, code {}", response.body().string(), response.code());
                } catch (IOException e) {
                    LOGGER.warn("Failed to extract body from response", e.getMessage());
                }
            }

            success(request, response);

        } catch (SocketTimeoutException e) {
            throw new IndexFailedException(e);
        } catch (IOException e) {
            throw new IndexFailedException(e);
        } catch (RuntimeException e) {
            throw new IndexFailedException(e);
        }*/
    }

    /**
     * Handles the response from OkHttp client to determine if the response was ok, is retryable or not
     * @param request - Used for logging purposed if the request was a 400 BadRequest
     * @param response
     */
    private void handleResponse(RequestContext request, Response response) {



        if (response.code() == 200) {
            ElasticSearchBulkResponse bulkResponse = ElasticSearchBulkResponse.parse(
                    request.signableRequest, response);
            if (bulkResponse.hasErrors()) {
                //List<JsonEvent> failedItems = bulkResponse.getRetryableItems(request.signableRequest);
                //LOGGER.debug("BulkResponse has errors, retrying {} failed items", failedItems.size());
                if (request.hasNextAttempt()) {
                    post(request.nextAttempt(bulkResponse));
                } else {
                    request.done(bulkResponse);
                }
            } else {
                request.done(bulkResponse);
            }
            return;
        }

        if (response.code() == 400) {
            request.error(createFatalIndexException(request.signableRequest, response));
            return;
        }

        request.error(IndexFailedException.of(response));
    }

    /**
     * Possible to override this method to perform postSuccess operations
     */
    protected void success(RequestSigner.SignableRequest request, Response response) {

    }


    /**
     * Converts the events to a BulkApi request
     */
    protected StringBuilder toBulkApiRequest(List<JsonEvent> batch) {

        StringBuilder builder = new StringBuilder();

        batch.stream().forEach((event ->
                builder.append(indexRowWithDateAndType(event))
                        .append("\n")
                        .append(event.toString(false))
                        .append("\n")));
        return builder;
    }

    private String indexRowWithDateAndType(JsonEvent event) {

        Optional<String> formattedType = type.format(event);
        if (!formattedType.isPresent()) {
            throw new IllegalStateException("Invalid value for type " + type.original());
        }

        ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
        ObjectNode data = OBJECT_MAPPER.createObjectNode();

        // Prepare for adding day to index for each event
        if (indexIsPrefix) {
            LocalDate indexDate;
            // TODO: Not sure how to handle this... what should be the behaviour if the specified timestamp field
            //       does not exist
            if (event.has(this.timestampField)) {
                 indexDate = LocalDate.parse(event.valueAsString(this.timestampField).substring(0, 10),
                        DateTimeFormatter.ISO_DATE);
            } else {
                indexDate = LocalDate.now();
            }
            data.put("_index",
                    index + indexDate.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")));
        } else {
            data.put("_index", index);
        }
        data.put("_type", formattedType.get());
        objectNode.set("index", data);

        return objectNode.toString();
     }

    protected void doOkHttpPost(RequestContext requestCtx)  {
        RequestBody body = RequestBody.create(TEXT, requestCtx.signableRequest.payload().get());
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .headers(Headers.of(requestCtx.signableRequest.headers()))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Request request, IOException e) {
                requestCtx.error(e);
            }

            @Override
            public void onResponse(Response response) throws IOException {
                handleResponse(requestCtx, response);
            }
        });
    }


    public static FatalIndexException createFatalIndexException(RequestSigner.SignableRequest request, Response response) {
        byte[] bytes = request.payload().get();
        String responseBody;
        try {
            responseBody = response.body().string();
        } catch (IOException e) {
            responseBody = "Failed to extract responseBody due to : " + e.getMessage();
        }
        return new FatalIndexException(format("Received %s : %s \nResponse: %s\nRequest (3000 chars): %s",
                response.code(), response.message(), responseBody,
                new String(bytes, 0, Math.min(3000, bytes.length))));
    }

    private class ElasticSearchRequest implements RequestSigner.SignableRequest {

        private final List<JsonEvent> events;
        private final Optional<byte[]> payload;
        private final URL url;
        public final Map<String, String> headers;




        public ElasticSearchRequest(List<JsonEvent> events, URL url) {
            this.events = events;
            this.url = url;
            headers = MapWrap.of("host", url.getHost()).toMap();
            payload = Optional.of(toBulkApiRequest(events).toString().getBytes());
        }

        @Override
        public String uri() {
            return "/_bulk";
        }

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public Map<String, String> queryParams() {
            return Collections.emptyMap();
        }

        @Override
        public Map<String, String> headers() {
            return headers;
        }

        @Override
        public Optional<byte[]> payload() {
            return payload;
        }

        @Override
        public  List<JsonEvent> original() {
            return events;
        }

        @Override
        public void addSignedHeaders(Map<String, String> headers) {
            this.headers.putAll(headers);
        }
    }

    /**
     * Contains state in order to track retries as well as returning response to pipeline.
     */
    private class RequestContext {

        private static final int MAX = 20; // hmm... fingers crossed.

        public final ReplaySubject<ElasticSearchBulkResponseEvent> subject = ReplaySubject.createWithSize(1);;

        /**
         * Keep them here until we are done, then return them
         */
        public final List<JsonEvent> events;

        public  ElasticSearchBulkResponseEvent response;

        /**
         * Request to execute
         */
        public RequestSigner.SignableRequest signableRequest;

        /**
         * Attempt count
         */
        public AtomicInteger attempt = new AtomicInteger(1);

        public RequestContext(List<JsonEvent> events, RequestSigner.SignableRequest signableRequest) {
            this.events = events;
            this.signableRequest = signableRequest;

        }

        public boolean hasNextAttempt() {
            return attempt.get() > MAX ? false : true;
        }


        public RequestContext nextAttempt(ElasticSearchBulkResponse result) {
            updateResponseEvent(result);
            this.signableRequest = failedRecords(result);
            this.attempt.incrementAndGet();
            if (!hasNextAttempt()) {
                FatalIndexException ex = FatalIndexException.of("Too many retries");
                error(ex);
                throw ex;
            }
            // Wait a few ms until we try again
            try {
                LOGGER.debug("Waiting 500ms before next attempt ({})", attempt);
                Thread.sleep(500); // To high? Configurable?
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return this;
        }

        private synchronized void updateResponseEvent(ElasticSearchBulkResponse bulkResponse) {
            if (this.response == null) {
                response = ElasticSearchBulkResponseEvent.of(bulkResponse);
            } else {
                response = response.nextAttempt(bulkResponse);
            }
        }

        private RequestSigner.SignableRequest failedRecords(ElasticSearchBulkResponse result) {
            List<JsonEvent> retryableItems = result.getRetryableItems(this.signableRequest);

            return new ElasticSearchRequest(retryableItems, url);
        }

        public void done(ElasticSearchBulkResponse bulkResponse) {
            // Fix return ALL + ES response
            updateResponseEvent(bulkResponse);
            LOGGER.debug("Done() free {}, max {}", Runtime.getRuntime().freeMemory(), Runtime.getRuntime().maxMemory());
            this.subject.onNext(response);
            this.subject.onCompleted();
        }

        public void error(Throwable t) {
            this.subject.onError(t);
        }
    }
}
