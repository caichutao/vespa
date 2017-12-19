// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Retries request on config server a few times before giving up. Assumes that all requests should be sent with
 * content-type application/json
 *
 * @author dybdahl
 */
public class ConfigServerHttpRequestExecutor {
    private static final PrefixLogger NODE_ADMIN_LOGGER = PrefixLogger.getNodeAdminLogger(ConfigServerHttpRequestExecutor.class);
    private static final int MAX_LOOPS = 2;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CloseableHttpClient client;
    private final List<URI> configServerHosts;
    private int pauseBetweenRetriesMs = 10_000;

    @Override
    public void finalize() throws Throwable {
        try {
            client.close();
        } catch (Exception e) {
            NODE_ADMIN_LOGGER.warning("Ignoring exception thrown when closing client against " + configServerHosts, e);
        }

        super.finalize();
    }

    public void eliminatePauseBetweenRetriesForTesting() {
        pauseBetweenRetriesMs = 0;
    }

    public static ConfigServerHttpRequestExecutor create(Collection<URI> configServerUris) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(getConnectionSocketFactoryRegistry());
        cm.setMaxTotal(200); // Increase max total connections to 200, which should be enough

        // Have experienced hang in socket read, which may have been because of
        // system defaults, therefore set explicit timeouts. Set arbitrarily to
        // 15s > 10s used by Orchestrator lock timeout.
        int timeoutMs = 15_000;
        RequestConfig requestBuilder = RequestConfig.custom()
                .setConnectTimeout(timeoutMs) // establishment of connection
                .setConnectionRequestTimeout(timeoutMs) // connection from connection manager
                .setSocketTimeout(timeoutMs) // waiting for data
                .build();

        return new ConfigServerHttpRequestExecutor(randomizeConfigServerUris(configServerUris),
                                                   HttpClientBuilder.create()
                                                           .setDefaultRequestConfig(requestBuilder)
                                                           .disableAutomaticRetries()
                                                           .setUserAgent("node-admin")
                                                           .setConnectionManager(cm).build());
    }

    private static Registry<ConnectionSocketFactory> getConnectionSocketFactoryRegistry() {
        try {
            SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                    new SSLContextBuilder().loadTrustMaterial(null, (arg0, arg1) -> true).build(),
                    NoopHostnameVerifier.INSTANCE);

            return RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.getSocketFactory())
                    .register("https", sslSocketFactory)
                    .build();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to create SSL context", e);
        }
    }

    ConfigServerHttpRequestExecutor(List<URI> configServerHosts, CloseableHttpClient client) {
        this.configServerHosts = configServerHosts;
        this.client = client;
    }

    public interface CreateRequest {
        HttpUriRequest createRequest(URI configServerUri) throws JsonProcessingException, UnsupportedEncodingException;
    }

    private <T> T tryAllConfigServers(CreateRequest requestFactory, Class<T> wantedReturnType) {
        Exception lastException = null;
        for (int loopRetry = 0; loopRetry < MAX_LOOPS; loopRetry++) {
            for (URI configServer : configServerHosts) {
                if (lastException != null) {
                    try {
                        // Avoid overloading the config server
                        Thread.sleep(pauseBetweenRetriesMs);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                final CloseableHttpResponse response;
                try {
                    response = client.execute(requestFactory.createRequest(configServer));
                } catch (Exception e) {
                    // Failure to communicate with a config server is not abnormal, as they are
                    // upgraded at the same time as Docker hosts.
                    if (e.getMessage().indexOf("(Connection refused)") > 0) {
                        NODE_ADMIN_LOGGER.info("Connection refused to " + configServer + " (upgrading?), will try next");
                    } else {
                        NODE_ADMIN_LOGGER.warning("Failed to communicate with " + configServer + ", will try next: " + e.getMessage());
                    }
                    lastException = e;
                    continue;
                }

                try {
                    Optional<HttpException> retryableException = HttpException.handleStatusCode(
                            response.getStatusLine().getStatusCode(),
                            "Config server " + configServer);
                    if (retryableException.isPresent()) {
                        lastException = retryableException.get();
                        continue;
                    }

                    try {
                        return mapper.readValue(response.getEntity().getContent(), wantedReturnType);
                    } catch (IOException e) {
                        throw new RuntimeException("Response didn't contain nodes element, failed parsing?", e);
                    }
                } finally {
                    try {
                        response.close();
                    } catch (IOException e) {
                        NODE_ADMIN_LOGGER.warning("Ignoring exception from closing response", e);
                    }
                }
            }
        }

        throw new RuntimeException("All requests against the config servers ("
                + configServerHosts + ") failed, last as follows:", lastException);
    }

    public <T> T put(String path, Optional<Object> bodyJsonPojo, Class<T> wantedReturnType) {
        return tryAllConfigServers(configServer -> {
            HttpPut put = new HttpPut(configServer.resolve(path));
            setContentTypeToApplicationJson(put);
            if (bodyJsonPojo.isPresent()) {
                put.setEntity(new StringEntity(mapper.writeValueAsString(bodyJsonPojo.get())));
            }
            return put;
        }, wantedReturnType);
    }

    public <T> T patch(String path, Object bodyJsonPojo, Class<T> wantedReturnType) {
        return tryAllConfigServers(configServer -> {
            HttpPatch patch = new HttpPatch(configServer.resolve(path));
            setContentTypeToApplicationJson(patch);
            patch.setEntity(new StringEntity(mapper.writeValueAsString(bodyJsonPojo)));
            return patch;
        }, wantedReturnType);
    }

    public <T> T delete(String path, Class<T> wantedReturnType) {
        return tryAllConfigServers(configServer ->
                new HttpDelete(configServer.resolve(path)), wantedReturnType);
    }

    public <T> T get(String path, Class<T> wantedReturnType) {
        return tryAllConfigServers(configServer ->
                new HttpGet(configServer.resolve(path)), wantedReturnType);
    }

    public <T> T post(String path, Object bodyJsonPojo, Class<T> wantedReturnType) {
        return tryAllConfigServers(configServer -> {
            HttpPost post = new HttpPost(configServer.resolve(path));
            setContentTypeToApplicationJson(post);
            post.setEntity(new StringEntity(mapper.writeValueAsString(bodyJsonPojo)));
            return post;
        }, wantedReturnType);
    }

    private void setContentTypeToApplicationJson(HttpRequestBase request) {
        request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
    }

    // Shuffle config server URIs to balance load
    private static List<URI> randomizeConfigServerUris(Collection<URI> configServerUris) {
        List<URI> shuffledConfigServerHosts = new ArrayList<>(configServerUris);
        Collections.shuffle(shuffledConfigServerHosts);
        return shuffledConfigServerHosts;
    }

}
