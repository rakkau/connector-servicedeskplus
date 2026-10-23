/*
 * Copyright 2026 RAKKAU
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.rakkau.midpoint.servicedeskplus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.regex.Pattern;

/**
 * Thin HTTP layer for the ServiceDesk Plus REST API v3 ({@code /api/v3/users}).
 *
 * <p>Handles authentication (technician API key), the per-connection TLS policy (JVM-wide defaults are never
 * modified) and the translation of HTTP / API status codes into ConnId exceptions.</p>
 */
final class SdpHttpClient {

    private static final Log LOG = Log.getLog(SdpHttpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ACCEPT = "application/vnd.manageengine.sdp.v3+json";
    private static final String USERS_PATH = "/api/v3/users";
    private static final int SDP_OK = 2000;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    // Heuristics over the SDP error text; to be confirmed against the real messages of the target SDP build.
    private static final Pattern DUPLICATE = Pattern.compile(
            "(?i)already\\s+(exist|in\\s+use|present)|duplicate|must\\s+be\\s+unique|is\\s+not\\s+unique");
    private static final Pattern NOT_FOUND = Pattern.compile("(?i)not\\s+found|does\\s+not\\s+exist|no\\s+such");

    private record Response(int status, String body, String location) { }

    private final ServiceDeskConfiguration cfg;
    private final String baseUrl;
    private final SSLSocketFactory insecureSocketFactory;

    SdpHttpClient(ServiceDeskConfiguration cfg) {
        this.cfg = cfg;
        this.baseUrl = cfg.getBaseUrl().trim().replaceAll("/+$", "");
        if (cfg.isTrustAllCertificates()) {
            LOG.warn("trustAllCertificates is ENABLED: TLS certificates and host names are NOT validated for this resource.");
            this.insecureSocketFactory = buildInsecureSocketFactory();
        } else {
            this.insecureSocketFactory = null;
        }
    }

    // ------------------------------------------------------------------ high level API

    /**
     * Lists users. When {@code searchField} is not null, the filter is evaluated by ServiceDesk Plus
     * ({@code list_info.search_fields}).
     */
    JsonNode listUsers(int startIndex, int rowCount, String searchField, String searchValue) {
        ObjectNode input = MAPPER.createObjectNode();
        ObjectNode listInfo = input.putObject("list_info");
        listInfo.put("start_index", startIndex);
        listInfo.put("row_count", rowCount);
        listInfo.put("sort_field", "name");
        listInfo.put("sort_order", "asc");
        if (searchField != null && searchValue != null) {
            listInfo.putObject("search_fields").put(searchField, searchValue);
        }
        return call("GET", USERS_PATH, input, false);
    }

    JsonNode getUser(String id) {
        return call("GET", userPath(id), null, true);
    }

    JsonNode createUser(ObjectNode user) {
        return call("POST", USERS_PATH, wrap(user), false);
    }

    JsonNode updateUser(String id, ObjectNode user) {
        return call("PUT", userPath(id), wrap(user), true);
    }

    void deleteUser(String id) {
        call("DELETE", userPath(id), null, true);
    }

    // ------------------------------------------------------------------ request / response handling

    private JsonNode call(String method, String path, ObjectNode inputData, boolean objectScoped) {
        Response response = execute(method, path, inputData);
        return interpret(response, method, path, objectScoped);
    }

    private Response execute(String method, String path, ObjectNode inputData) {
        try {
            String query = "";
            byte[] body = null;
            if (inputData != null) {
                String form = "input_data=" + URLEncoder.encode(MAPPER.writeValueAsString(inputData), StandardCharsets.UTF_8);
                if ("GET".equals(method)) {
                    query = "?" + form;
                } else {
                    body = form.getBytes(StandardCharsets.UTF_8);
                }
            }
            URL url = URI.create(baseUrl + path + query).toURL();
            HttpURLConnection conn = open(url, method);
            conn.setRequestProperty("Accept", ACCEPT);
            conn.setRequestProperty("authtoken", reveal(cfg.getAuthToken()));
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            }
            return exchange(conn, body);
        } catch (IOException e) {
            throw mapIo(e);
        }
    }

    private JsonNode interpret(Response r, String method, String path, boolean objectScoped) {
        int http = r.status();
        LOG.ok("ServiceDesk Plus {0} {1} -> HTTP {2}", method, path, http);

        if (http >= 300 && http < 400) {
            throw new ConnectorException("ServiceDesk Plus answered with a redirect (HTTP " + http
                    + (r.location() != null ? " to " + r.location() : "")
                    + "). Check that baseUrl uses the right scheme, port and context path.");
        }
        JsonNode root = parseQuietly(r.body());
        if (http >= 200 && http < 300) {
            int code = statusCode(root);
            if (code == SDP_OK || (code == -1 && r.body().isBlank())) {
                return root;
            }
            if (code == -1) {
                throw new ConnectorException("Unexpected ServiceDesk Plus response (no response_status) for "
                        + method + " " + path);
            }
            // HTTP 2xx but the API reports a failure in response_status
            throw mapError(400, root, objectScoped, method, path);
        }
        throw mapError(http, root, objectScoped, method, path);
    }

    private RuntimeException mapError(int http, JsonNode root, boolean objectScoped, String method, String path) {
        String detail = describe(root);
        String msg = "ServiceDesk Plus " + method + " " + path + " failed with HTTP " + http
                + (detail.isEmpty() ? "" : " (" + detail + ")");
        if (http == 401) {
            return new InvalidCredentialException(msg + ": API key rejected or expired");
        }
        if (http == 403) {
            return new PermissionDeniedException(msg);
        }
        if (http == 409 || DUPLICATE.matcher(detail).find()) {
            return new AlreadyExistsException(msg);
        }
        if (http == 404 || (objectScoped && NOT_FOUND.matcher(detail).find())) {
            return objectScoped
                    ? new UnknownUidException(msg)
                    : new ConnectorException(msg + ": endpoint not found, check baseUrl and API version");
        }
        if ((http == 400 || http == 422) && !"GET".equals(method)) {
            return new InvalidAttributeValueException(msg);
        }
        if (http == 502 || http == 503 || http == 504) {
            return new ConnectionFailedException(msg);
        }
        return new ConnectorException(msg);
    }

    private static RuntimeException mapIo(IOException e) {
        if (e instanceof SSLException) {
            return new ConnectionFailedException("ServiceDesk Plus TLS error (" + e.getMessage() + "). If the server uses "
                    + "a private CA or a self-signed certificate, import it into the JVM trust store "
                    + "(test environments only: trustAllCertificates=true).", e);
        }
        if (e instanceof SocketTimeoutException || e instanceof ConnectException || e instanceof UnknownHostException) {
            return new ConnectionFailedException("ServiceDesk Plus is not reachable: " + e.getMessage(), e);
        }
        return new ConnectorIOException("ServiceDesk Plus I/O error: " + e.getMessage(), e);
    }

    // ------------------------------------------------------------------ low level helpers

    private HttpURLConnection open(URL url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (insecureSocketFactory != null && conn instanceof HttpsURLConnection https) {
            // Per-connection override only: the JVM-wide defaults are never touched.
            https.setSSLSocketFactory(insecureSocketFactory);
            https.setHostnameVerifier((host, session) -> true);
        }
        conn.setRequestMethod(method);
        conn.setConnectTimeout(cfg.getTimeoutSeconds() * 1000);
        conn.setReadTimeout(cfg.getTimeoutSeconds() * 1000);
        conn.setInstanceFollowRedirects(false); // never forward the API key to another location
        return conn;
    }

    private Response exchange(HttpURLConnection conn, byte[] body) throws IOException {
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
        }
        int status = conn.getResponseCode();
        String location = conn.getHeaderField("Location");
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = "";
        if (stream != null) {
            try (InputStream in = stream) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new Response(status, text, location);
    }

    private static SSLSocketFactory buildInsecureSocketFactory() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new ConnectorException("Cannot initialise the trust-all TLS context", e);
        }
    }

    private static ObjectNode wrap(ObjectNode user) {
        ObjectNode input = MAPPER.createObjectNode();
        input.set("user", user);
        return input;
    }

    private static String userPath(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new UnknownUidException("Invalid ServiceDesk Plus user identifier");
        }
        return USERS_PATH + "/" + id;
    }

    private static JsonNode parseQuietly(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    private static int statusCode(JsonNode root) {
        JsonNode status = root.path("response_status");
        if (status.isArray() && !status.isEmpty()) {
            status = status.get(0);
        }
        return status.path("status_code").asInt(-1);
    }

    private static String describe(JsonNode root) {
        JsonNode status = root.path("response_status");
        if (status.isArray() && !status.isEmpty()) {
            status = status.get(0);
        }
        StringBuilder sb = new StringBuilder();
        JsonNode messages = status.path("messages");
        if (messages.isArray()) {
            for (JsonNode m : messages) {
                String text = m.path("message").asText("");
                String field = m.path("field").asText("");
                if (!text.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    if (!field.isEmpty()) {
                        sb.append(field).append(": ");
                    }
                    sb.append(text);
                }
            }
        }
        if (sb.length() == 0) {
            sb.append(status.path("status").asText(""));
        }
        return sb.toString();
    }

    private static String reveal(GuardedString secret) {
        StringBuilder sb = new StringBuilder();
        secret.access(chars -> sb.append(chars));
        return sb.toString();
    }
}
