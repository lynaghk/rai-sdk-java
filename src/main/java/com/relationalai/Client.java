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
import java.net.URISyntaxException;
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
    public static final int DEFAULT_PORT = 443;

    public String region = DEFAULT_REGION;
    public String scheme = DEFAULT_SCHEME;
    public String host = DEFAULT_HOST;
    public int port = DEFAULT_PORT;
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

    public Client(String region, String scheme, String host, int port, Credentials credentials) {
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
            this.port = Integer.parseInt(cfg.port);
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
    static String encodeValue(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e.toString());
        }
    }

    // Ensure the given path is a URL, prefixing with scheme://host:port if
    // needed.
    URI makeUri(String path) {
        return makeUri(path, null);
    }

    // Ensure the given path is a URL, prefixing with scheme://host:port if
    // needed then encode and append the given query params.
    URI makeUri(String path, QueryParams params) {
        var query = params != null ? params.encode() : null;
        try {
            return new URI(this.scheme, null, this.host, this.port, path, query, null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.toString());
        }
    }

    // Returns the default User-Agent string.
    static String userAgent() {
        return String.format("rai-sdk-java/%s", "0.0.1"); // todo: version
    }

    // Returns an HttpRequest.Builder constructed from the given args.
    HttpRequest.Builder newRequestBuilder(String method, String path, QueryParams params) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        builder.uri(makeUri(path, params));
        builder.method(method, BodyPublishers.noBody());
        return builder;
    }

    // Returns an HttpRequest.Builder constructed from the given args.
    HttpRequest.Builder newRequestBuilder(
            String method, String path, QueryParams params, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        builder.uri(makeUri(path, params));
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
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

    public String delete(String path)
            throws HttpError, InterruptedException, IOException {
        return delete(path, null, null);
    }

    public String delete(String path, QueryParams params, String body)
            throws HttpError, InterruptedException, IOException {
        return request("DELETE", path, params, body);
    }

    public String get(String path)
            throws HttpError, InterruptedException, IOException {
        return get(path, null);
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

    // Returns a URL path constructed from the given parts.
    String makePath(String... parts) {
        return String.join("/", parts);
    }

    static <T> T deserialize(String s, Class<T> cls) {
        return JsonIterator.deserialize(s, cls);
    }

    static String serialize(Entity entity) {
        return entity.toString();
    }

    static String serialize(Entity entity, int indent) {
        return entity.toString(indent);
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

    //
    // Databases
    //

    public CreateDatabaseResponse createDatabase(String database, String engine)
            throws HttpError, InterruptedException, IOException {
        return createDatabase(database, engine, null, false);
    }

    public CreateDatabaseResponse createDatabase(
            String database, String engine, String source, boolean overwrite)
            throws HttpError, InterruptedException, IOException {
        var mode = createMode(source, overwrite);
        var tx = new Transaction(this.region, database, engine, mode, false, source);
        var rsp = post(PATH_TRANSACTION, tx.queryParams(), tx.payload());
        return deserialize(rsp, CreateDatabaseResponse.class);
    }

    public DeleteDatabaseResponse deleteDatabase(String database)
            throws HttpError, InterruptedException, IOException {
        var req = new DeleteDatabaseRequest(database);
        var rsp = delete(PATH_DATABASE, null, serialize(req));
        return deserialize(rsp, DeleteDatabaseResponse.class);
    }

    public Database getDatabase(String database)
            throws HttpError, InterruptedException, IOException {
        var params = new QueryParams();
        params.put("name", database);
        var rsp = get(PATH_DATABASE, params);
        var data = deserialize(rsp, GetDatabaseResponse.class).database;
        if (data.length == 0)
            throw new HttpError(404);
        return data[0];
    }

    public Database[] listDatabases()
            throws HttpError, InterruptedException, IOException {
        return listDatabases(null);
    }

    public Database[] listDatabases(String state)
            throws HttpError, InterruptedException, IOException {
        QueryParams params = null;
        if (state != null) {
            params = new QueryParams();
            params.put("state", state);
        }
        String rsp = get(PATH_DATABASE, params);
        return deserialize(rsp, ListDatabasesResponse.class).databases;
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

    public OAuthClientExtra createOAuthClient(String name)
            throws HttpError, InterruptedException, IOException {
        return createOAuthClient(name, null);
    }

    public OAuthClientExtra createOAuthClient(String name, String[] permissions)
            throws HttpError, InterruptedException, IOException {
        var req = new CreateOAuthClientRequest(name, permissions);
        var rsp = post(PATH_OAUTH_CLIENTS, null, serialize(req));
        return deserialize(rsp, CreateOAuthClientResponse.class).client;
    }

    public DeleteOAuthClientResponse deleteOAuthClient(String id)
            throws HttpError, InterruptedException, IOException {
        var rsp = delete(makePath(PATH_OAUTH_CLIENTS, id));
        return deserialize(rsp, DeleteOAuthClientResponse.class);
    }

    public OAuthClient findOAuthClient(String name)
            throws HttpError, InterruptedException, IOException {
        var clients = listOAuthClients();
        for (var client : clients) {
            if (client.name.equals(name))
                return client;
        }
        return null;
    }

    public OAuthClientExtra getOAuthClient(String id)
            throws HttpError, InterruptedException, IOException {
        var rsp = get(makePath(PATH_OAUTH_CLIENTS, id));
        return deserialize(rsp, GetOAuthClientResponse.class).client;
    }

    public OAuthClient[] listOAuthClients()
            throws HttpError, InterruptedException, IOException {
        var rsp = get(PATH_OAUTH_CLIENTS);
        return deserialize(rsp, ListOAuthClientsResponse.class).clients;
    }

    // Users

    public User createUser(String email)
            throws HttpError, InterruptedException, IOException {
        return createUser(email, null);
    }

    public User createUser(String email, String[] roles)
            throws HttpError, InterruptedException, IOException {
        var req = new CreateUserRequest(email, roles);
        var rsp = post(PATH_USERS, null, serialize(req));
        return deserialize(rsp, CreateUserResponse.class).user;
    }

    public Object deleteUser(String id)
            throws HttpError, InterruptedException, IOException {
        var rsp = delete(makePath(PATH_USERS, id));
        return deserialize(rsp, DeleteUserResponse.class);
    }

    public User disableUser(String id)
            throws HttpError, InterruptedException, IOException {
        return updateUser(id, "INACTIVE");
    }

    public User enableUser(String id)
            throws HttpError, InterruptedException, IOException {
        return updateUser(id, "ACTIVE");
    }

    // Returns the User with the given email.
    public User findUser(String email)
            throws HttpError, InterruptedException, IOException {
        var users = listUsers();
        for (var user : users) {
            if (user.email.equals(email))
                return user;
        }
        return null;
    }

    public User getUser(String id)
            throws HttpError, InterruptedException, IOException {
        var rsp = get(makePath(PATH_USERS, id));
        return deserialize(rsp, GetUserResponse.class).user;
    }

    public User[] listUsers()
            throws HttpError, InterruptedException, IOException {
        var rsp = get(PATH_USERS);
        return deserialize(rsp, ListUsersResponse.class).users;
    }

    public User updateUser(String id, String status)
            throws HttpError, InterruptedException, IOException {
        return updateUser(id, new UpdateUserRequest(status));
    }

    public User updateUser(String id, String[] roles)
            throws HttpError, InterruptedException, IOException {
        return updateUser(id, new UpdateUserRequest(roles));
    }

    public User updateUser(String id, String status, String[] roles)
            throws HttpError, InterruptedException, IOException {
        return updateUser(id, new UpdateUserRequest(status, roles));
    }

    public User updateUser(String id, UpdateUserRequest req)
            throws HttpError, InterruptedException, IOException {
        var rsp = patch(makePath(PATH_USERS, id), null, serialize(req));
        return deserialize(rsp, UpdateUserResponse.class).user;
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

    public List<Edb> listEdbs(String database, String engine)
            throws HttpError, InterruptedException, IOException {
        return null; // todo
    }

    // Models

    // Delete the named model.
    public TransactionResult deleteModel(String database, String engine, String name)
            throws HttpError, InterruptedException, IOException {
        var tx = new Transaction(this.region, database, engine, "OPEN");
        var action = DbAction.makeDeleteModelAction(name);
        var data = tx.payload(action);
        var rsp = post(PATH_TRANSACTION, tx.queryParams(), data);
        return deserialize(rsp, TransactionResult.class);
    }

    // Delete the list of named models.
    public TransactionResult deleteModel(String database, String engine, String[] names)
            throws HttpError, InterruptedException, IOException {
        var tx = new Transaction(this.region, database, engine, "OPEN");
        var actions = DbAction.makeDeleteModelsAction(names);
        var data = tx.payload(actions);
        var rsp = post(PATH_TRANSACTION, tx.queryParams(), data);
        return deserialize(rsp, TransactionResult.class);
    }

    public Model getModel(String database, String engine, String model)
            throws HttpError, InterruptedException, IOException {
        var models = listModels(database, engine);
        for (var item : models) {
            if (item.name.equals(model))
                return item;
        }
        throw new HttpError(404);
    }

    public TransactionResult installModel(
            String database, String engine, String name, String model)
            throws HttpError, InterruptedException, IOException {
        var tx = new Transaction(this.region, database, engine, "OPEN", false);
        var action = DbAction.makeInstallAction(name, model);
        var data = tx.payload(action);
        var rsp = post(PATH_TRANSACTION, tx.queryParams(), data);
        return deserialize(rsp, TransactionResult.class);
    }

    public TransactionResult installModels(
            String database, String engine, Map<String, String> models)
            throws HttpError, InterruptedException, IOException {
        var tx = new Transaction(this.region, database, engine, "OPEN", false);
        var actions = DbAction.makeInstallAction(models);
        var data = tx.payload(actions);
        var rsp = post(PATH_TRANSACTION, tx.queryParams(), data);
        return deserialize(rsp, TransactionResult.class);
    }

    public String[] listModelNames(String database, String engine)
            throws HttpError, InterruptedException, IOException {
        var models = listModels(database, engine);
        String[] result = new String[models.length];
        for (var i = 0; i < models.length; ++i)
            result[i] = models[i].name;
        return result;
    }

    public Model[] listModels(String database, String engine)
            throws HttpError, InterruptedException, IOException {
        var tx = new Transaction(this.region, database, engine, "OPEN", true);
        var data = tx.payload(DbAction.makeListModelsAction());
        var rsp = post(PATH_TRANSACTION, tx.queryParams(), data);
        var actions = deserialize(rsp, ListModelsResponse.class).actions;
        if (actions.length == 0)
            return new Model[] {};
        return actions[0].result.models;
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

    static void test() throws HttpError, InterruptedException, IOException {
        var cfg = Config.loadConfig("~/.rai/config");
        var client = new Client(cfg);

        Object rsp;

        // transaction

        rsp = client.execute("bradlo-test", "bradlo-test", "1 + 2 + 3");
        System.out.println(serialize(rsp, 4));

        // engines

        rsp = client.listEngines();
        System.out.println(serialize(rsp, 4));

        rsp = client.getEngine("bradlo-test");
        System.out.println(serialize(rsp, 4));

        // databases

        rsp = client.deleteDatabase("bradlo-test");
        System.out.println(serialize(rsp, 4));

        rsp = client.createDatabase("bradlo-test", "bradlo-test");
        System.out.println(serialize(rsp, 4));

        rsp = client.listDatabases();
        System.out.println(serialize(rsp, 4));

        rsp = client.listDatabases("CREATED");
        System.out.println(serialize(rsp, 4));

        rsp = client.getDatabase("bradlo-test");
        System.out.println(serialize(rsp, 4));
    }

    static void testOAuthClients() throws HttpError, InterruptedException, IOException {
        var cfg = Config.loadConfig("~/.rai/config");
        var client = new Client(cfg);

        Object rsp;

        rsp = client.listOAuthClients();
        System.out.println(serialize(rsp, 4));

        var oauthClient = client.findOAuthClient("sdk-test");
        if (oauthClient != null)
            client.deleteOAuthClient(oauthClient.id);

        String[] permissions = null;
        rsp = client.createOAuthClient("sdk-test", permissions);
        System.out.println(serialize(rsp, 4));

        rsp = client.listOAuthClients();
        System.out.println(serialize(rsp, 4));

        oauthClient = client.findOAuthClient("sdk-test");
        assert oauthClient != null;
        rsp = client.getOAuthClient(oauthClient.id);
        System.out.println(serialize(rsp, 4));

        rsp = client.deleteOAuthClient(oauthClient.id);
        System.out.println(serialize(rsp, 4));

        rsp = client.listOAuthClients();
        System.out.println(serialize(rsp, 4));

    }

    static void testUsers() throws HttpError, InterruptedException, IOException {
        var cfg = Config.loadConfig("~/.rai/config");
        var client = new Client(cfg);

        Object rsp;

        rsp = client.listUsers();
        System.out.println(serialize(rsp, 4));

        // cleanup if necessarry
        var user = client.findUser("sdk-test@relational.ai");
        if (user != null)
            client.deleteUser(user.id);

        rsp = client.createUser("sdk-test@relational.ai");
        System.out.println(serialize(rsp, 4));

        user = client.findUser("sdk-test@relational.ai");
        assert user != null;

        rsp = client.getUser(user.id);
        System.out.println(serialize(rsp, 4));

        rsp = client.disableUser(user.id);
        System.out.println(serialize(rsp, 4));

        rsp = client.enableUser(user.id);
        System.out.println(serialize(rsp, 4));

        rsp = client.updateUser(user.id, "INACTIVE");
        System.out.println(serialize(rsp, 4));

        rsp = client.updateUser(user.id, "ACTIVE");
        System.out.println(serialize(rsp, 4));

        String[] rolesAdmin = {"admin", "user"};
        rsp = client.updateUser(user.id, rolesAdmin);
        System.out.println(serialize(rsp, 4));

        String[] rolesUser = {"user"};
        rsp = client.updateUser(user.id, "INACTIVE", rolesUser);
        System.out.println(serialize(rsp, 4));

        rsp = client.deleteUser(user.id);
        System.out.println(serialize(rsp, 4));
    }

    static void testModels() throws HttpError, InterruptedException, IOException {
        var cfg = Config.loadConfig("~/.rai/config");
        var client = new Client(cfg);

        Object rsp;

        // todo: test installModels
        // todo: test deleteModels

        rsp = client.listModels("bradlo-test", "bradlo-test");
        System.out.println(serialize(rsp, 4));

        rsp = client.listModelNames("bradlo-test", "bradlo-test");
        System.out.println(serialize(rsp, 4));

        rsp = client.installModel(
                "bradlo-test", "bradlo-test",
                "hello", "def R = \"hello\",\"world!\"");
        System.out.println(serialize(rsp, 4));

        rsp = client.getModel("bradlo-test", "bradlo-test", "hello");
        System.out.println(serialize(rsp, 4));

        rsp = client.deleteModel("bradlo-test", "bradlo-test", "hello");
        System.out.println(serialize(rsp, 4));

        rsp = client.listModelNames("bradlo-test", "bradlo-test");
        System.out.println(serialize(rsp, 4));
    }

    static void run() throws HttpError, InterruptedException, IOException {
        test();
        testOAuthClients();
        testUsers();
        testModels();
    };

    public static void main(String[] args) {
        try {
            run();
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}