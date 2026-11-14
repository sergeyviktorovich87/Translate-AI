package ru.personal.lingua;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Official Sign in with ChatGPT personal/open-source flow. Not a scraper of ChatGPT cookies. */
final class ChatAuth {
    interface Listener { void result(boolean ok, String message); }
    private final Activity activity;
    private final SecureTokenStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile ServerSocket listener;
    private static final String AUTH = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    ChatAuth(Activity activity) { this.activity = activity; this.store = new SecureTokenStore(activity); }

    void startLogin(Listener done) {
        LocalLog.event(activity, "oauth_start");
        io.execute(() -> {
            try {
                // Android Chrome can return to a localhost loopback bound only to this device.
                ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                server.setSoTimeout(300000);
                listener = server;
                String redirect = "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback";
                String state = secureRandom(32), nonce = secureRandom(32), verifier = secureRandom(48);
                String challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)),
                        Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
                JSONObject old = store.load();
                String clientId = old == null ? "dynamic_agent_client" : old.optString("client_id", "dynamic_agent_client");
                Uri.Builder b = Uri.parse(AUTH).buildUpon()
                        .appendQueryParameter("client_id", clientId)
                        .appendQueryParameter("ext_agent_host_id", store.hostId())
                        .appendQueryParameter("response_type", "code")
                        .appendQueryParameter("redirect_uri", redirect)
                        .appendQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                        .appendQueryParameter("resource", RESOURCE)
                        .appendQueryParameter("state", state)
                        .appendQueryParameter("nonce", nonce)
                        .appendQueryParameter("code_challenge_method", "S256")
                        .appendQueryParameter("code_challenge", challenge);
                if (old == null) b.appendQueryParameter("agent_name_hint", "Translate AI");
                else {
                    if (old.has("id_token")) b.appendQueryParameter("id_token_hint", old.optString("id_token"));
                    if (old.has("email")) b.appendQueryParameter("login_hint", old.optString("email"));
                }
                Uri destination = b.build();
                ui.post(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, destination);
                        i.addCategory(Intent.CATEGORY_BROWSABLE);
                        activity.startActivity(i);
                    } catch (Exception e) { done.result(false, "На телефоне не найден браузер."); }
                });
                try (ServerSocket closeable = server; Socket socket = server.accept()) {
                    socket.setSoTimeout(15000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String first = reader.readLine();
                    if (first == null || !first.startsWith("GET /auth/callback?")) throw new SecurityException("Invalid callback path");
                    String[] terms = first.split(" ");
                    if (terms.length < 3) throw new SecurityException("Invalid request");
                    String pathAndQuery = terms[1];
                    Uri result = Uri.parse("http://127.0.0.1" + pathAndQuery);
                    String returnedState = result.getQueryParameter("state");
                    if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),
                            (returnedState == null ? "" : returnedState).getBytes(StandardCharsets.US_ASCII)))
                        throw new SecurityException("State check failed");
                    String error = result.getQueryParameter("error");
                    if (error != null) {
                        sendHttp(socket, "Авторизация отменена. Вернитесь в приложение.");
                        throw new SecurityException("User declined authorization");
                    }
                    String code = result.getQueryParameter("code");
                    if (code == null || code.isEmpty()) throw new SecurityException("Authorization code missing");
                    String issued = result.getQueryParameter("client_id");
                    String effectiveClient;
                    if (old == null) {
                        if (issued == null || !issued.startsWith("oaiapp_"))
                            throw new SecurityException("Missing issued client ID");
                        effectiveClient = issued;
                    } else {
                        effectiveClient = old.getString("client_id");
                        if (issued != null && !issued.equals(effectiveClient)) throw new SecurityException("Unexpected client ID");
                    }
                    LocalLog.event(activity, "oauth_callback_validated");
                    sendHttp(socket, "Данные авторизации получены. Завершите подключение в Translate AI.");
                    LocalLog.event(activity, "oauth_token_exchange_started");
                    JSONObject token = postForm(TOKEN, params(
                            "grant_type", "authorization_code", "client_id", effectiveClient,
                            "code", code, "code_verifier", verifier,
                            "redirect_uri", redirect, "resource", RESOURCE));
                    LocalLog.event(activity, "oauth_token_exchanged");
                    JSONObject claims = verifyIdToken(token.getString("id_token"), effectiveClient, nonce);
                    LocalLog.event(activity, "oauth_identity_validated");
                    if (old != null && !old.optString("subject").equals(claims.getString("sub")))
                        throw new SecurityException("Signed in with a different account");
                    String scopes = token.optString("scope", "");
                    if (!Arrays.asList(scopes.split(" ")).contains("chatgpt.tokens.use.direct"))
                        throw new SecurityException("ChatGPT plan usage not granted");
                    token.put("client_id", effectiveClient);
                    token.put("subject", claims.getString("sub"));
                    token.put("email", claims.optString("email", ""));
                    token.put("scope", scopes);
                    token.put("expires_at", System.currentTimeMillis() / 1000L + token.optLong("expires_in", 3600));
                    // Atomic encrypted save: old profile survives any failure before this point.
                    store.save(token);
                    LocalLog.event(activity, "oauth_success");
                    ui.post(() -> done.result(true, "Аккаунт подключён: " + claims.optString("email", "пользователь")));
                } finally { listener = null; }
            } catch (Exception e) {
                LocalLog.event(activity, "oauth_failed_" + e.getClass().getSimpleName());
                ui.post(() -> done.result(false, "Не удалось войти: " + safeError(e)));
            }
        });
    }

    private void sendHttp(Socket socket, String text) throws IOException {
        String content = "<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width'>" +
                "<title>Личный переводчик</title><p style='font:20px sans-serif;margin:50px'>" + text + "</p>";
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n" +
                "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8)); out.write(body); out.flush();
    }

    boolean isConnected() {
        try { return store.load() != null; } catch (Exception e) { return false; }
    }

    String accountStatus() {
        try {
            JSONObject obj = store.load();
            return obj == null ? "Не подключён" : "Подключён: " + obj.optString("email", "аккаунт");
        } catch (Exception ignored) { return "Хранилище недоступно"; }
    }

    String accessToken() throws Exception {
        JSONObject current = store.load();
        if (current == null) throw new IllegalStateException("Сначала войдите через ChatGPT");
        if (System.currentTimeMillis() / 1000L < current.optLong("expires_at") - 90)
            return current.getString("access_token");
        JSONObject updated = postForm(TOKEN, params(
                "grant_type", "refresh_token", "client_id", current.getString("client_id"),
                "refresh_token", current.getString("refresh_token"), "resource", RESOURCE));
        // Merge only fields from successfully refreshed token set.
        Iterator<String> keys = updated.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            current.put(key, updated.get(key));
        }
        current.put("expires_at", System.currentTimeMillis() / 1000L + updated.optLong("expires_in", 3600));
        store.save(current);
        LocalLog.event(activity, "token_refreshed");
        return current.getString("access_token");
    }

    interface StreamListener {
        void onModel(String model);
        void onDelta(String accumulated);
        void onComplete(String reply);
        void onError(String safeMessage);
    }

    /** Requests inference with a user-approved token. Only locally supplied history is used. */
    void ask(String instructions, JSONArray history, String text, String photoDataUrl,
             StreamListener listener) {
        io.execute(() -> {
            HttpURLConnection conn = null;
            try {
                LocalLog.event(activity, photoDataUrl == null ?
                        "request_text_started" : "request_image_started");
                String bearer = accessToken();
                JSONObject catalog = getJson(RESOURCE + "/models", bearer);
                JSONArray models = catalog.optJSONArray("models");
                if (models == null) models = catalog.optJSONArray("data");
                if (models == null || models.length() == 0)
                    throw new IllegalStateException("Аккаунт не вернул список моделей");
                String model = null;
                for (int i=0; i<models.length(); i++) {
                    JSONObject m = models.optJSONObject(i);
                    if (m == null || !"list".equals(m.optString("visibility", "list"))) continue;
                    String slug = m.optString("slug", m.optString("id", ""));
                    if (slug.isEmpty()) continue;
                    if (model == null) model=slug;
                    if ("gpt-6.1-sol".equals(slug)) { model=slug; break; }
                }
                if (model == null) throw new IllegalStateException("Нет моделей, доступных аккаунту");
                final String selectedModel=model;
                ui.post(() -> listener.onModel(selectedModel));

                JSONArray inputs = new JSONArray();
                if (history != null) {
                    for (int i=Math.max(0, history.length()-12);i<history.length();i++) {
                        inputs.put(history.getJSONObject(i));
                    }
                }
                JSONObject user = new JSONObject().put("role","user");
                if (photoDataUrl != null) {
                    JSONArray parts = new JSONArray();
                    parts.put(new JSONObject().put("type","input_text")
                            .put("text",text.trim().isEmpty()?"Опиши это изображение.":text));
                    parts.put(new JSONObject().put("type","input_image")
                            .put("image_url",photoDataUrl));
                    user.put("content", parts);
                } else user.put("content", text);
                inputs.put(user);
                JSONObject request = new JSONObject()
                        .put("model",model)
                        .put("instructions",instructions)
                        .put("input",inputs)
                        .put("store",false)
                        .put("stream",true);

                conn=connection(RESOURCE+"/responses",bearer);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type","application/json");
                conn.setRequestProperty("Accept","text/event-stream");
                conn.setDoOutput(true);
                conn.setReadTimeout(120000);
                try(OutputStream out=conn.getOutputStream()){
                    out.write(request.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status=conn.getResponseCode();
                if (status<200 || status>=300) {
                    String code="";
                    try (InputStream err=conn.getErrorStream()) {
                        if (err != null) {
                            JSONObject json=new JSONObject(readText(err));
                            JSONObject obj=json.optJSONObject("error");
                            if (obj!=null) code=obj.optString("code","");
                        }
                    } catch (Exception ignored) { }
                    throw new IOException("HTTP "+status+(code.matches("[a-zA-Z0-9_]{1,100}")?
                            " ("+code+")":""));
                }
                StringBuilder result=new StringBuilder();
                boolean done=false;
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                        conn.getInputStream(),StandardCharsets.UTF_8))){
                    String line;
                    while((line=reader.readLine())!=null){
                        if(!line.startsWith("data: "))continue;
                        String raw=line.substring(6);
                        if("[DONE]".equals(raw))break;
                        JSONObject event;
                        try{event=new JSONObject(raw);}
                        catch(Exception ignored){continue;}
                        String type=event.optString("type","");
                        if("response.output_text.delta".equals(type)){
                            result.append(event.optString("delta",""));
                            final String current=result.toString();
                            ui.post(() -> listener.onDelta(current));
                        }else if("response.completed".equals(type))done=true;
                        else if("response.failed".equals(type)||
                                "response.incomplete".equals(type)){
                            JSONObject resp=event.optJSONObject("response");
                            JSONObject err=resp==null?null:resp.optJSONObject("error");
                            String reason=err==null?"response_failed":
                                    err.optString("code","response_failed");
                            throw new IOException(reason.matches("[a-zA-Z0-9_]{1,100}")?
                                    reason:"response_failed");
                        }
                    }
                }
                if(!done)throw new IOException("Поток ответа прервался");
                LocalLog.event(activity,"request_completed");
                final String output=result.toString().trim();
                ui.post(() -> listener.onComplete(output));
            }catch(Exception e){
                LocalLog.event(activity,"request_failed_"+e.getClass().getSimpleName());
                final String message=safeError(e);
                ui.post(() -> listener.onError(message));
            }finally{if(conn!=null)conn.disconnect();}
        });
    }

    static String targetForSpeech(String fullResult) {
        String first = fullResult.split("\\r?\\n", 2)[0].trim();
        return first.replaceFirst("^(翻译|Перевод)\\s*[:：]\\s*", "");
    }

    void signOut(Listener done) {
        io.execute(() -> {
            boolean remoteConfirmed = false;
            try {
                JSONObject current = store.load();
                if (current != null && current.has("refresh_token")) {
                    JSONObject metadata = getJson("https://auth.openai.com/.well-known/openid-configuration", null);
                    String url = metadata.optString("revocation_endpoint", "");
                    if (url.startsWith("https://auth.openai.com/")) {
                        postForm(url, params("token", current.getString("refresh_token"),
                                "token_type_hint", "refresh_token", "client_id", current.getString("client_id")));
                        remoteConfirmed = true;
                    }
                } else remoteConfirmed = true;
            } catch (Exception ignored) { LocalLog.event(activity, "revoke_unconfirmed"); }
            store.clear();
            boolean confirmed = remoteConfirmed;
            ui.post(() -> done.result(true, confirmed ? "Выход выполнен" :
                    "Локальный выход выполнен. Отзыв доступа на сервере не подтверждён; проверьте доступ приложений в настройках ChatGPT."));
        });
    }

    private static String secureRandom(int bytes) {
        byte[] data = new byte[bytes]; SECURE_RANDOM.nextBytes(data);
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }
    static String safeError(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "превышено время ожидания сервера";
        if (e instanceof java.net.UnknownHostException) return "нет соединения с сервером";
        if (e instanceof SecurityException) return "проверка безопасности или разрешений не прошла";
        if (e instanceof IllegalStateException) return e.getMessage();
        if (e instanceof IOException && e.getMessage() != null &&
                e.getMessage().matches("[\\w ()0-9-]{1,140}"))
            return e.getMessage();
        return e.getClass().getSimpleName();
    }
    private static HttpURLConnection connection(String url, String bearer) throws Exception {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol())) throw new SecurityException("HTTPS required");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(false); c.setUseCaches(false);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        return c;
    }
    private static void checkStatus(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
    }
    private static Map<String, String> params(String... pair) {
        if (pair.length % 2 != 0) throw new IllegalArgumentException("Missing form value");
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < pair.length; i += 2) result.put(pair[i], pair[i + 1]);
        return result;
    }
    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[4096];
        int n;
        while ((n = in.read(bytes)) >= 0) {
            if (out.size() + n > 2_000_000) throw new IOException("Unexpectedly large response");
            out.write(bytes, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    private static JSONObject getJson(String url, String bearer) throws Exception {
        HttpURLConnection c = connection(url, bearer);
        try {
            checkStatus(c);
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(readText(in));
            }
        } finally { c.disconnect(); }
    }
    private static JSONObject postForm(String url, Map<String, String> args) throws Exception {
        HttpURLConnection c = connection(url, null);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        StringJoiner body = new StringJoiner("&");
        for (Map.Entry<String, String> p : args.entrySet())
            body.add(URLEncoder.encode(p.getKey(), "UTF-8") + "=" +
                    URLEncoder.encode(p.getValue(), "UTF-8"));
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            checkStatus(c);
            try (InputStream in = c.getInputStream()) {
                String data = readText(in);
                return data.trim().isEmpty() ? new JSONObject() : new JSONObject(data);
            }
        } finally { c.disconnect(); }
    }

    private static JSONObject verifyIdToken(String token, String clientId, String nonce) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new SecurityException("Malformed ID token");
        JSONObject header = new JSONObject(new String(Base64.decode(parts[0], Base64.URL_SAFE), StandardCharsets.UTF_8));
        JSONObject claims = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg"))) throw new SecurityException("Untrusted JWT algorithm");
        String kid = header.getString("kid");
        JSONObject metadata = getJson("https://auth.openai.com/.well-known/openid-configuration", null);
        String issuer = metadata.getString("issuer");
        String jwksUrl = metadata.getString("jwks_uri");
        if (!issuer.equals(claims.optString("iss")) || !"https://auth.openai.com".equals(issuer))
            throw new SecurityException("Issuer does not match");
        if (!jwksUrl.startsWith("https://auth.openai.com/")) throw new SecurityException("Untrusted key endpoint");
        JSONArray keys = getJson(jwksUrl, null).getJSONArray("keys");
        boolean signed = false;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.getJSONObject(i);
            if (!kid.equals(key.optString("kid")) || !"RSA".equals(key.optString("kty"))) continue;
            BigInteger n = new BigInteger(1, Base64.decode(key.getString("n"), Base64.URL_SAFE));
            BigInteger e = new BigInteger(1, Base64.decode(key.getString("e"), Base64.URL_SAFE));
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            signed = verifier.verify(Base64.decode(parts[2], Base64.URL_SAFE));
            break;
        }
        if (!signed) throw new SecurityException("ID token signature invalid");
        boolean audienceMatch = clientId.equals(claims.optString("aud"));
        JSONArray audArray = claims.optJSONArray("aud");
        if (audArray != null) for (int i = 0; i < audArray.length(); i++)
            if (clientId.equals(audArray.optString(i))) audienceMatch = true;
        if (!audienceMatch) throw new SecurityException("ID token audience invalid");
        if (!nonce.equals(claims.optString("nonce"))) throw new SecurityException("ID token nonce invalid");
        long now = System.currentTimeMillis() / 1000L;
        if (claims.optLong("exp") <= now - 30 || claims.optLong("iat") > now + 120)
            throw new SecurityException("ID token is expired or not yet issued");
        if (claims.optString("sub").isEmpty()) throw new SecurityException("Missing subject");
        return claims;
    }
    void destroy() { try { if (listener != null) listener.close(); } catch (IOException ignored) { } io.shutdownNow(); }
}
