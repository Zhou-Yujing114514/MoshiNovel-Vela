package com.moshinovel;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 摩柿后端（morax.kdns.fr，HTTPS）客户端。
 *
 * 已验证的后端行为（须与后端保持一致）：
 *   1) 登录：POST {BASE}/api/login，body JSON {"username":..,"password":..}
 *      → 从响应 Set-Cookie 中取名为 session 的 cookie 值。
 *   2) 书架：GET {BASE}/api/bookshelf，请求头 Cookie: session=<值>
 *      → JSON {"items":[{"title","author","download_url","task_id"}, ...]}
 *   3) 下载：GET <download_url>（同 session Cookie）→ TXT 文本流。
 *
 * 安全说明：
 *   - 不硬编码任何用户账密；账密由手环端输入后随协议 payload 传入。
 *   - 固定服务器证书 SHA-1 指纹（防中间人）。指纹来自后端实测：
 *       B2:C3:C9:FC:EA:DF:2D:51:9F:DA:57:54:23:FE:BB:D7:22:17:2C:07
 *     注意：OkHttp 自带 CertificatePinner 需要的是「SPKI 的 SHA-256(Base64)」，
 *     而我们手里是整证 SHA-1 指纹，因此这里用自定义 TrustManager 直接比对指纹；
 *     若后端更换证书，必须同步更新 PINNED_SHA1_FINGERPRINT。
 */
public class MoraxClient {

    private static final String TAG = "MoraxClient";

    public static final String BASE = "https://morax.kdns.fr";
    private static final String LOGIN_PATH = "/api/login";
    private static final String SHELF_PATH = "/api/bookshelf";

    /** 服务器证书 SHA-1 指纹（小写冒号分隔）。 */
    private static final String PINNED_SHA1_FINGERPRINT =
            "b2:c3:c9:fc:ea:df:2d:51:9f:da:57:54:23:fe:bb:d7:22:17:2c:07";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 书架书目（字段名已映射为桥接协议字段，见 architecture.md §3.2）。 */
    public static class Book {
        public final String title;
        public final String author;
        public final String downloadUrl;
        public final long taskId;

        Book(String title, String author, String downloadUrl, long taskId) {
            this.title = title;
            this.author = author;
            this.downloadUrl = downloadUrl;
            this.taskId = taskId;
        }
    }

    private final OkHttpClient http;
    private String session; // 登录成功后的 session cookie 值

    public MoraxClient() {
        this.http = buildClient();
    }

    private static OkHttpClient buildClient() {
        X509TrustManager pinningTm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 本端不做客户端证书校验。
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("服务器未提供证书");
                }
                try {
                    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                    byte[] fp = sha1.digest(chain[0].getEncoded());
                    String actual = hexLower(fp);
                    if (!PINNED_SHA1_FINGERPRINT.equals(actual)) {
                        throw new CertificateException(
                                "证书指纹不匹配，可能遭遇中间人攻击：expected="
                                        + PINNED_SHA1_FINGERPRINT + " actual=" + actual);
                    }
                } catch (CertificateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CertificateException("证书指纹校验失败: " + e.getMessage());
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new X509TrustManager[]{pinningTm}, new java.security.SecureRandom());
            SSLSocketFactory factory = sc.getSocketFactory();
            return new OkHttpClient.Builder()
                    .sslSocketFactory(factory, pinningTm)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS) // 下载 TXT 可能较慢
                    .build();
        } catch (Exception e) {
            // 指纹固定失败会让所有 HTTPS 请求不可用，这里退化为运行时异常暴露问题。
            throw new RuntimeException("初始化摩柿 HTTPS 客户端失败: " + e.getMessage(), e);
        }
    }

    /**
     * 登录：POST /api/login，成功后把 session cookie 写入本客户端，并回传。
     *
     * @return session 字符串
     * @throws IOException HTTP 非 2xx、未取到 session cookie、网络异常
     */
    public synchronized String login(String username, String password) throws IOException {
        String body = "{\"username\":\"" + escapeJson(username)
                + "\",\"password\":\"" + escapeJson(password) + "\"}";
        Request req = new Request.Builder()
                .url(BASE + LOGIN_PATH)
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("登录失败（HTTP " + resp.code() + "），请检查用户名或密码");
            }
            String sessionVal = extractSessionCookie(resp.headers("Set-Cookie"));
            if (sessionVal == null || sessionVal.isEmpty()) {
                throw new IOException("登录响应中未找到 session cookie");
            }
            this.session = sessionVal;
            Log.i(TAG, "登录成功，session 已建立（长度=" + sessionVal.length() + "）");
            return sessionVal;
        }
    }

    /**
     * 书架：GET /api/bookshelf，返回映射后的书目列表。
     */
    public List<Book> bookshelf() throws IOException {
        ensureSession();
        Request req = new Request.Builder()
                .url(BASE + SHELF_PATH)
                .header("Cookie", "session=" + session)
                .get()
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("获取书架失败（HTTP " + resp.code() + "）");
            }
            ResponseBody rb = resp.body();
            if (rb == null) throw new IOException("书架响应为空");
            JsonObject root = JsonParser.parseString(rb.string()).getAsJsonObject();
            JsonArray items = root.has("items") ? root.getAsJsonArray("items") : new JsonArray();
            List<Book> out = new ArrayList<>(items.size());
            for (JsonElement el : items) {
                JsonObject o = el.getAsJsonObject();
                String title = o.has("title") ? o.get("title").getAsString() : "";
                String author = o.has("author") ? o.get("author").getAsString() : "";
                String url = o.has("download_url") ? o.get("download_url").getAsString() : "";
                long taskId = o.has("task_id") ? o.get("task_id").getAsLong() : 0L;
                out.add(new Book(title, author, url, taskId));
            }
            return out;
        }
    }

    /**
     * 打开一本书的下载流（TXT 文本）。调用方负责关闭 ResponseBody。
     * 进度/分片由 BridgeService 边读边下发。
     */
    public ResponseBody openDownload(String downloadUrl) throws IOException {
        ensureSession();
        // download_url 可能是相对路径，也可能已是完整 URL。
        String url = downloadUrl.startsWith("http") ? downloadUrl : BASE + downloadUrl;
        Request req = new Request.Builder()
                .url(url)
                .header("Cookie", "session=" + session)
                .get()
                .build();
        Response resp = http.newCall(req).execute();
        if (!resp.isSuccessful()) {
            resp.close();
            throw new IOException("下载失败（HTTP " + resp.code() + "）");
        }
        ResponseBody body = resp.body();
        if (body == null) {
            resp.close();
            throw new IOException("下载响应为空");
        }
        return body;
    }

    public synchronized String getSession() {
        return session;
    }

    private void ensureSession() throws IOException {
        if (session == null || session.isEmpty()) {
            throw new IOException("未登录：session 为空");
        }
    }

    /** 从 Set-Cookie 列表里取名为 session 的 cookie 值。 */
    private static String extractSessionCookie(List<String> setCookies) {
        for (String line : setCookies) {
            // 形如：session=xxxx; Path=/; HttpOnly
            int idx = line.indexOf("session=");
            if (idx < 0) continue;
            String rest = line.substring(idx + "session=".length());
            int semi = rest.indexOf(';');
            return (semi >= 0 ? rest.substring(0, semi) : rest).trim();
        }
        return null;
    }

    private static String hexLower(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
