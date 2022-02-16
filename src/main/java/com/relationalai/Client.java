/*
 * Copyright 2022 RelationalAI, Inc.
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

package com.relationalai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.jsoniter.JsonIterator;
import com.jsoniter.output.JsonStream;

public class Client {
    public static final String DEFAULT_REGION = "us-east";
    public static final String DEFAULT_SCHEME = "https";
    public static final String DEFAULT_HOST = "azure.relationalai.com";
    public static final String DEFAULT_PORT = "443";

    public String region = DEFAULT_REGION;
    public String scheme = DEFAULT_SCHEME;
    public String host = DEFAULT_HOST;
    public String port = DEFAULT_PORT;
    public Credentials credentials;

    HttpClient httpClient;

    static Map<String, String> _defaultHeaders = null;

    static {
        _defaultHeaders = new HashMap<String, String>();
        _defaultHeaders.put("Accept", "application/json");
        _defaultHeaders.put("Content-Type", "application/json");
        _defaultHeaders.put("User-Agent", userAgent());
    }

    public Client() {}

    public Client(String region, String scheme, String host, String port, Credentials credentials) {
        this.region = region;
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.credentials = credentials;
    }

    public Client(Config cfg) {
        if (cfg.region != null)
            this.region = cfg.region;
        if (cfg.scheme != null)
            this.scheme = cfg.scheme;
        if (cfg.host != null)
            this.host = cfg.host;
        if (cfg.port != null)
            this.port = cfg.port;
        this.credentials = cfg.credentials;
    }

    // Returns the current `HttpClient` instance, creating one if necessarry.
    HttpClient httpClient() {
        if (this.httpClient == null) {
            this.httpClient = HttpClient.newBuilder().build();
        }
        return this.httpClient;
    }

    // Use the HttpClient instance configured by the caller.
    public void withHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    static final String fetchAccessTokenFormat = "{" +
            "\"client_id\":\"%s\"," +
            "\"client_secret\":\"%s\"," +
            "\"audience\":\"%s\"," +
            "\"grant_type\":\"client_credentials\"}";

    String fetchAccessTokenBody(ClientCredentials credentials) {
        assert credentials != null;
        String audience = String.format("https://%s", this.host);
        return String.format(
                fetchAccessTokenFormat,
                credentials.clientId,
                credentials.clientSecret,
                audience);
    }

    public AccessToken fetchAccessToken(ClientCredentials credentials)
            throws HttpError, InterruptedException, IOException {
        String body = fetchAccessTokenBody(credentials);
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        builder.POST(HttpRequest.BodyPublishers.ofString(body));
        builder.uri(URI.create(credentials.clientCredentialsUrl));
        addHeaders(builder, _defaultHeaders);
        HttpRequest request = builder.build();
        HttpResponse<String> response =
                httpClient().send(request, HttpResponse.BodyHandlers.ofString());
        var data = response.body();
        var statusCode = response.statusCode();
        if (statusCode >= 400 && statusCode < 500)
            throw new HttpError(statusCode, data);
        return deserialize(data, AccessToken.class);
    }

    // todo: add callback func
    public AccessToken getAccessToken(ClientCredentials credentials)
            throws HttpError, InterruptedException, IOException {
        AccessToken accessToken = credentials.accessToken;
        if (accessToken == null || accessToken.isExpired()) {
            accessToken = fetchAccessToken(credentials);
            credentials.accessToken = accessToken;
        }
        credentials.accessToken = fetchAccessToken(credentials);
        return accessToken;
    }

    static boolean containsInsensitive(Map<String, String> headers, String key) {
        key = key.toLowerCase();
        for (String k : headers.keySet()) {
            if (k.toLowerCase() == key)
                return true;
        }
        return false;
    }

    // Ensures that the given headers contain the required default values.
    static Map<String, String> defaultHeaders(Map<String, String> headers) {
        if (headers == null)
            return _defaultHeaders;
        if (!containsInsensitive(headers, "Accept"))
            headers.put("Accept", "application/json");
        if (!containsInsensitive(headers, "Content-Type"))
            headers.put("Content-Type", "application/json");
        if (!containsInsensitive(headers, "User-Agent"))
            headers.put("User-Agent", userAgent());
        return headers;
    }

    // Encode an element of a query parameter.
    static String encodeParam(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }

    // Ensure the given path is a URL, prefixing with scheme://host:port if
    // needed.
    String makeUrl(String path) {
        if (path.startsWith("/")) {
            path = String.format("%s://%s:%s%s", scheme, host, port, path);
        }
        return path;
    }

    // Ensure the given path is a URL, prefixing with scheme://host:port if
    // needed, encode at append the given query params.
    String makeUrl(String path, QueryParams params) throws UnsupportedEncodingException {
        path = makeUrl(path);
        if (params == null)
            return path;
        var query = params.encode();
        if (query.length() == 0)
            return path;
        return path + "?" + query;
    }

    // Returns the default User-Agent string.
    static String userAgent() {
        return String.format("rai-sdk-java/%s", "0.0.1"); // todo: version
    }

    // Returns an HttpRequest.Builder constructed from the given args.
    HttpRequest.Builder newRequestBuilder(String method, String path, QueryParams params)
            throws UnsupportedEncodingException {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        builder.uri(URI.create(makeUrl(path, params)));
        builder.method(method, BodyPublishers.noBody());
        return builder;
    }

    // Returns an HttpRequest.Builder constructed from the given args.
    HttpRequest.Builder newRequestBuilder(
            String method, String path, QueryParams params, String body)
            throws UnsupportedEncodingException {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        builder.uri(URI.create(makeUrl(path, params)));
        builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        return builder;
    }

    void addHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet())
            builder.header(entry.getKey(), entry.getValue());
    }

    // Authenticate the request using the given credentials, if any.
    void authenticate(HttpRequest.Builder builder, Credentials credentials)
            throws HttpError, IOException, InterruptedException {
        if (credentials == null)
            return;
        if (credentials instanceof ClientCredentials) {
            authenticate(builder, (ClientCredentials) credentials);
            return;
        }
        throw new RuntimeException("invalid credential type");
    }

    // Authenticate the given request using the given `ClientCredentials`.
    void authenticate(HttpRequest.Builder builder, ClientCredentials credentials)
            throws HttpError, InterruptedException, IOException {
        AccessToken accessToken = getAccessToken(credentials);
        builder.header("Authorization", String.format("Bearer %s", accessToken.token));
    }

    String sendRequest(HttpRequest.Builder builder)
            throws HttpError, InterruptedException, IOException {
        addHeaders(builder, _defaultHeaders);
        authenticate(builder, this.credentials);
        HttpRequest request = builder.build();
        // printRequest(request);
        HttpResponse<String> response =
                httpClient().send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        if (statusCode >= 400 && statusCode < 500)
            throw new HttpError(statusCode, response.body());
        return response.body();
    }

    static void printRequest(HttpRequest request) {
        System.out.printf("%s %s\n", request.method(), request.uri());
        for (Map.Entry<String, List<String>> entry : request.headers().map().entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue().get(0);
            System.out.printf("%s: %s\n", k, v);
        }
        // todo: figure out how to get the body from a request (non-trivial)
    }

    public String request(String method, String path, QueryParams params)
            throws HttpError, InterruptedException, IOException {
        return sendRequest(newRequestBuilder(method, path, params));
    }

    public String request(String method, String path, QueryParams params, String body)
            throws HttpError, InterruptedException, IOException {
        return sendRequest(newRequestBuilder(method, path, params, body));
    }

    public String delete(String path, QueryParams params, String body)
            throws HttpError, InterruptedException, IOException {
        return request("DELETE", path, params, body);
    }

    public String get(String path, QueryParams params)
            throws HttpError, InterruptedException, IOException {
        return request("GET", path, params);
    }

    public String patch(String path, QueryParams params, String body)
            throws HttpError, InterruptedException, IOException {
        return request("PATCH", path, params, body);
    }

    public String post(String path, QueryParams params, String body)
            throws HttpError, InterruptedException, IOException {
        return request("POST", path, params, body);
    }

    public String put(String path, QueryParams params, String body)
            throws HttpError, InterruptedException, IOException {
        return request("PUT", path, params, body);
    }

    //
    // REST APIs
    //

    static final String PATH_DATABASE = "/database";
    static final String PATH_ENGINE = "/compute";
    static final String PATH_OAUTH_CLIENTS = "/oauth-clients";
    static final String PATH_TRANSACTION = "/transaction";
    static final String PATH_USERS = "/users";

    static <T> T deserialize(String s, Class<T> cls) {
        return JsonIterator.deserialize(s, cls);
    }

    static String serialize(Model model) {
        return model.toString();
    }

    static String serialize(Model model, int indent) {
        return model.toString(indent);
    }

    static String serialize(Object obj) {
        return serialize(obj, 0);
    }

    static String serialize(Object obj, int indent) {
        var output = new ByteArrayOutputStream();
        JsonStream.setIndentionStep(indent);
        JsonStream.serialize(obj, output);
        return output.toString();
    }

    // Databases

    public CreateDatabaseResponse createDatabase(String database, String engine)
            throws HttpError, InterruptedException, IOException {
        return createDatabase(database, engine, null, false);
    }

    public CreateDatabaseResponse createDatabase(
            String database, String engine, String source, boolean overwrite)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public DeleteDatabaseResponse deleteDatabase(String database)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public GetDatabaseResponse getDatabase(String database)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public ListDatabasesResponse listDatabases()
            throws HttpError, InterruptedException, IOException {
        return listDatabases(null);
    }

    public ListDatabasesResponse listDatabases(String state)
            throws HttpError, InterruptedException, IOException {
        QueryParams params = null;
        if (state != null) {
            params = new QueryParams();
            params.put("state", state);
        }
        String rsp = get(PATH_DATABASE, params);
        return deserialize(rsp, ListDatabasesResponse.class);
    }

    public UpdateDatabaseResponse updateDatabase(String database, UpdateDatabaseRequest req) {
        return null; // todo
    }

    // Engines

    public Engine createEngine(String engine)
            throws HttpError, InterruptedException, IOException {
        return createEngine(engine, "XS");
    }

    public Engine createEngine(String engine, String size)
            throws HttpError, InterruptedException, IOException {
        var req = new CreateEngineRequest(this.region, engine, size);
        var rsp = put(PATH_ENGINE, null, serialize(req));
        return deserialize(rsp, CreateEngineResponse.class).engine;
    }

    public DeleteEngineResponse deleteEngine(String engine)
            throws HttpError, InterruptedException, IOException {
        var req = new DeleteEngineRequest(engine);
        var rsp = delete(PATH_ENGINE, null, serialize(req));
        return deserialize(rsp, DeleteEngineResponse.class);
    }

    public Engine getEngine(String engine)
            throws HttpError, InterruptedException, IOException {
        var params = new QueryParams();
        params.put("name", engine);
        params.put("deleted_on", "");
        var rsp = get(PATH_ENGINE, params);
        var data = deserialize(rsp, GetEngineResponse.class).engines;
        if (data.length == 0)
            throw new HttpError(404);
        return data[0];
    }

    public Engine[] listEngines()
            throws HttpError, InterruptedException, IOException {
        return listEngines(null);
    }

    public Engine[] listEngines(String state)
            throws HttpError, InterruptedException, IOException {
        QueryParams params = null;
        if (state != null) {
            params = new QueryParams();
            params.put("state", state);
        }
        var rsp = get(PATH_ENGINE, params);
        return deserialize(rsp, ListEnginesResponse.class).engines;
    }

    // OAuth clients

    public CreateOAuthClientResponse createOAuthClient(String name, String[] permissions)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public DeleteOAuthClientResponse deleteOAuthClient(String id)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public GetOAuthClientResponse getOAuthClient(String id)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public ListOAuthClientsResponse listOAuthClients()
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    // Users

    public CreateUserResponse createUser(String email, String[] roles)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public DisableUserResponse disableUser(String id)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public EnableUserResponse enableUser(String id)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public GetUserResponse getUser(String id)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    public ListUsersResponse listUsers()
            throws HttpError, InterruptedException, IOException {
        return null;
    }

    public UpdateUserResponse updateUser(String id, UpdateUserRequest req)
            throws HttpError, InterruptedException, IOException {
        return null;
    }

    // Transactions

    String createMode(String source, boolean overwrite) {
        if (source != null)
            return overwrite ? "CLONE_OVERWRITE" : "CLONE";
        else
            return overwrite ? "CREATE_OVERWRITE" : "CREATE";
    }

    public TransactionResult execute(String database, String engine, String source)
            throws HttpError, InterruptedException, IOException {
        return execute(database, engine, source, false, null);
    }

    public TransactionResult execute(
            String database, String engine, String source, boolean readonly)
            throws HttpError, InterruptedException, IOException {
        return execute(database, engine, source, readonly, null);
    }

    public TransactionResult execute(
            String database, String engine,
            String source, boolean readonly,
            Map<String, String> inputs)
            throws HttpError, InterruptedException, IOException {
        var tx = new Transaction(region, database, engine, "OPEN", readonly);
        var queryAction = DbAction.makeQueryAction(source, inputs);
        var data = tx.payload(queryAction);
        var rsp = post(PATH_TRANSACTION, tx.queryParams(), data);
        return JsonIterator.deserialize(rsp, TransactionResult.class);
    }

    // EDBs

    public List<Edb> listEdbs(String database, String engine) {
        return null; // todo
    }

    // Models

    public DeleteModelsResponse deleteModel(String database, String engine, String[] models) {
        return null; // todo
    }

    public GetModelResponse getModel(String database, String engine, String model) {
        return null; // todo
    }

    public InstallModelsResponse installModels(
            String database, String engine, Map<String, String> models) {
        return null; // todo
    }

    public ListModelsResponse listModels(String database, String engine) {
        return null; // todo
    }

    // Data loading

    public TransactionResult loadCSV(
            String database, String engine, String relation, String data, Object options) {
        return null; // todo
    }

    public TransactionResult loadJSON(String database, String relation, String data) {
        return null; // todo
    }

    // *** temporary ***

    public void run() throws HttpError, InterruptedException, IOException {
        var cfg = Config.loadConfig("~/.rai/config");
        var client = new Client(cfg);
        // var rsp = client.listDatabases("CREATED");
        // var rsp = client.execute("bradlo-test", "bradlo-test", "1 + 2 + 3");
        // var rsp = client.listEngines();
        var rsp = client.getEngine("bradlo-test");
        System.out.println(serialize(rsp, 4));
    }

    public static void main(String[] args) {
        Client client = new Client();
        try {
            client.run();
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
