package com.moshinovel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 桥接前台服务：手环 BLE → 手机网络 网关的主循环。
 *
 * 协议（architecture.md §3 + §11.2，冻结）：
 *   watch→phone 请求：{v:1, id, service:"moshi"|"void",
 *      action:"login"|"shelf"|"download"|"ping"|"http"|
 *             "vt_login"|"vt_rooms"|"vt_send"|"vt_quit", payload:{...}}
 *   phone→watch 响应：{v:1, id, ok, code, message, payload:{...}}
 *   phone→watch 推送：{v:1, id:"push-N", kind:"download_progress"|"download_chunk"
 *                      |"vt_message"|"vt_status", payload:{...}}
 *
 * 下载分片：每片 ≤ CHUNK_MAX=4000 个文本字符（与 watch-app/common/util.js 一致），
 * 边读边发 progress，最后一片 eof=true，再补发 percent=100 收尾，最后回配对响应。
 */
public class BridgeService extends Service implements WatchChannel.WatchMessageListener {

    private static final String TAG = "BridgeService";
    private static final String CHANNEL_ID = "moshinovel_bridge";
    private static final int NOTI_ID = 1001;

    /** 单片最大文本字符数（协议 §3.2 / util.js CHUNK_MAX=4000）。 */
    static final int CHUNK_MAX = 4000;

    /**
     * 通用 http 代理动作的响应 body 截断上限（64 KiB）。
     * 超过则截断并在 payload 里标 truncated:true；超大响应请改用 download 动作分片。
     */
    static final int HTTP_BODY_MAX = 64 * 1024;

    private MoraxClient morax;
    private WatchChannel channel;
    private OkHttpClient genericHttp; // action:'http' 用的通用 HTTP 客户端（用户任意 URL，不锁证书）
    private VoidTerminalClient vtClient; // 虚空终端 WSS 聊天客户端（service='void'，vt_* 动作）
    private HandlerThread workerThread;
    private Handler worker;

    /** 摩柿会话（login 成功后由 MoraxClient 持有，这里缓存登录用户名用于回包）。 */
    private String moshiUsername;
    private int pushFrameSeq = 0; // push-N 自增

    /** vt_asr：系统语音识别（一次一个会话，并发请求直接拒绝）。 */
    private static final int ASR_TIMEOUT_MS = 15000;
    private SpeechRecognizer asr;
    private String asrReqId;
    private boolean asrBusy = false;
    private final Runnable asrTimeoutTask = () -> finishAsrError("语音识别超时，请重试");

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotices();

        morax = new MoraxClient();
        // TODO(SDK 集成点): 接入官方 SDK 后，把这里换成 SDK 实现类（见 WatchChannel 顶部注释）。
        channel = new WatchChannel.LoggingWatchChannel();
        channel.setWatchMessageListener(this);
        channel.open();

        // action:'http' 通用代理用：用户传入任意 URL，不做摩柿证书固定（系统默认信任链）。
        genericHttp = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        // 虚空终端 WSS 聊天客户端：WSS 长连由本 App 持有，推送帧经 worker 串行转发手环。
        vtClient = new VoidTerminalClient(new VoidTerminalClient.Callback() {
            @Override public void onStatus(String state) {
                worker.post(() -> {
                    JsonObject p = new JsonObject();
                    p.addProperty("state", state);
                    pushFrame("vt_status", p);
                });
            }
            @Override public void onMessage(String type, String roomKey, String from,
                                            String fromName, String content) {
                worker.post(() -> {
                    JsonObject p = new JsonObject();
                    p.addProperty("type", type);
                    p.addProperty("roomKey", roomKey);
                    p.addProperty("from", from);
                    p.addProperty("fromName", fromName);
                    p.addProperty("content", content);
                    pushFrame("vt_message", p);
                });
            }
        });

        workerThread = new HandlerThread("bridge-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        Log.i(TAG, "BridgeService 已启动，等待手环上行报文");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /** WatchChannel 上行回调：可能在任意线程，切到 worker 串行处理。 */
    @Override
    public void onWatchMessage(final String json) {
        worker.post(() -> handleRequest(json));
    }

    // ── 协议分发 ────────────────────────────────────────────────────────────

    private void handleRequest(String json) {
        String reqId = null;
        try {
            JsonObject req = JsonParser.parseString(json).getAsJsonObject();
            reqId = req.has("id") ? req.get("id").getAsString() : "";
            String service = req.has("service") ? req.get("service").getAsString() : "";
            String action = req.has("action") ? req.get("action").getAsString() : "";
            JsonObject payload = req.has("payload") && req.get("payload").isJsonObject()
                    ? req.getAsJsonObject("payload") : new JsonObject();

            switch (action) {
                case "ping": {
                    JsonObject p = new JsonObject();
                    p.addProperty("pong", true);
                    respondOk(reqId, p);
                    break;
                }
                case "login": {
                    if (!"moshi".equals(service)) {
                        respondError(reqId, 10, "虚空终端暂未接入书源（buer.kdns.fr 无书架 API）");
                        return;
                    }
                    String username = payload.has("username") ? payload.get("username").getAsString() : "";
                    String password = payload.has("password") ? payload.get("password").getAsString() : "";
                    String session = morax.login(username, password);
                    moshiUsername = username;
                    JsonObject p = new JsonObject();
                    p.addProperty("session", session);
                    p.addProperty("username", username);
                    respondOk(reqId, p);
                    break;
                }
                case "shelf": {
                    if (!"moshi".equals(service)) {
                        respondError(reqId, 10, "虚空终端暂未接入书源（buer.kdns.fr 无书架 API）");
                        return;
                    }
                    List<MoraxClient.Book> books = morax.bookshelf();
                    JsonObject p = new JsonObject();
                    com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                    for (MoraxClient.Book b : books) {
                        JsonObject o = new JsonObject();
                        o.addProperty("title", b.title);
                        o.addProperty("author", b.author);
                        o.addProperty("downloadUrl", b.downloadUrl);
                        o.addProperty("taskId", b.taskId);
                        arr.add(o);
                    }
                    p.add("items", arr);
                    respondOk(reqId, p);
                    break;
                }
                case "download": {
                    if (!"moshi".equals(service)) {
                        respondError(reqId, 10, "虚空终端暂未接入书源（buer.kdns.fr 无书架 API）");
                        return;
                    }
                    String bookKey = payload.has("bookKey") ? payload.get("bookKey").getAsString() : "";
                    String url = payload.has("downloadUrl") ? payload.get("downloadUrl").getAsString() : "";
                    runDownload(reqId, bookKey, url);
                    break;
                }
                case "http": {
                    // 通用 HTTP 代理（fetch 等价通道，兼容社区插件 FetchBridge）：
                    // payload {method,url,headers,body} → 响应 {status,headers,body,truncated}
                    runHttpProxy(reqId, payload);
                    break;
                }
                // ── 虚空终端聊天（service='void'，architecture.md §11.2，冻结） ──
                case "vt_login": {
                    String username = payload.has("username") ? payload.get("username").getAsString() : "";
                    String password = payload.has("password") ? payload.get("password").getAsString() : "";
                    String token = vtClient.login(username, password);
                    JsonObject p = new JsonObject();
                    p.addProperty("token", token);
                    respondOk(reqId, p);
                    break;
                }
                case "vt_rooms": {
                    respondOk(reqId, vtClient.roomsPayload());
                    break;
                }
                case "vt_send": {
                    String type = payload.has("type") ? payload.get("type").getAsString() : "";
                    String id = payload.has("id") && !payload.get("id").isJsonNull()
                            ? payload.get("id").getAsString() : "";
                    String content = payload.has("content") ? payload.get("content").getAsString() : "";
                    vtClient.send(type, id, content);
                    respondOk(reqId, new JsonObject());
                    break;
                }
                case "vt_quit": {
                    vtClient.quit();
                    respondOk(reqId, new JsonObject());
                    break;
                }
                case "vt_asr": {
                    // 语音输入：手机侧调系统 SpeechRecognizer 转写一次 → {ok, text}
                    // （手环 9Pro 不能录音，见 spec/voice-input.md）
                    startAsr(reqId);
                    break;
                }
                default:
                    respondError(reqId, 2, "未知 action: " + action);
            }
        } catch (Exception e) {
            Log.w(TAG, "处理请求异常", e);
            respondError(reqId == null ? "" : reqId, 1, "桥接内部错误: " + e.getMessage());
        }
    }

    // ── 下载：progress + chunk 流 ───────────────────────────────────────────

    private void runDownload(String reqId, String bookKey, String downloadUrl) {
        ResponseBody body = null;
        try {
            body = morax.openDownload(downloadUrl);
            long total = body.contentLength(); // 可能为 -1（未知）
            Reader reader = new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8);
            char[] buf = new char[8192];
            StringBuilder pending = new StringBuilder();
            long bytes = 0;
            int seq = 0;
            int n;
            while ((n = reader.read(buf)) != -1) {
                pending.append(buf, 0, n);
                bytes += utf8Length(buf, 0, n);
                while (pending.length() >= CHUNK_MAX) {
                    String chunk = pending.substring(0, CHUNK_MAX);
                    pending.delete(0, CHUNK_MAX);
                    sendChunk(bookKey, seq++, chunk, false);
                }
                int percent = total > 0 ? (int) Math.min(99, bytes * 100 / total) : -1;
                sendProgress(bookKey, percent, bytes, total);
            }
            // 收尾：剩余不足一片的内容作为最后一片 eof=true
            sendChunk(bookKey, seq++, pending.toString(), true);
            sendProgress(bookKey, 100, bytes, total);

            JsonObject p = new JsonObject();
            p.addProperty("bookKey", bookKey);
            p.addProperty("bytes", bytes);
            respondOk(reqId, p);
        } catch (IOException e) {
            Log.w(TAG, "下载失败 bookKey=" + bookKey, e);
            respondError(reqId, 20, "下载失败: " + e.getMessage());
        } finally {
            if (body != null) body.close();
        }
    }

    // ── 通用 http 代理（fetch 等价通道） ────────────────────────────────────

    /**
     * action:'http' —— 替手环发起任意 HTTP(S) 请求。
     * 请求 payload: {method:"GET|POST|...", url:"https://...",
     *                headers:{"k":"v",...}, body:"文本(可选)"}
     * 响应 payload: {status:int, headers:{"k":"v"}, body:"文本", truncated:bool}
     * body 超过 HTTP_BODY_MAX(64KiB) 即截断并标 truncated:true；
     * 大文件下载请改用 action:'download'（分片流，见 runDownload）。
     */
    private void runHttpProxy(String reqId, JsonObject payload) {
        try {
            String method = payload.has("method") ? payload.get("method").getAsString() : "GET";
            String url = payload.has("url") ? payload.get("url").getAsString() : "";
            if (url.isEmpty()) {
                respondError(reqId, 1, "http 代理缺少 url");
                return;
            }
            String reqBody = payload.has("body") && !payload.get("body").isJsonNull()
                    ? payload.get("body").getAsString() : null;

            Request.Builder rb = new Request.Builder().url(url);
            // 请求头
            if (payload.has("headers") && payload.get("headers").isJsonObject()) {
                JsonObject hs = payload.getAsJsonObject("headers");
                for (Map.Entry<String, com.google.gson.JsonElement> e : hs.entrySet()) {
                    rb.header(e.getKey(), e.getValue().getAsString());
                }
            }
            // 方法 + 请求体
            String upper = method.toUpperCase();
            if (upper.equals("GET") || upper.equals("HEAD")) {
                rb.method(upper, null);
            } else {
                MediaType mt = null;
                if (payload.has("headers") && payload.getAsJsonObject("headers").has("Content-Type")) {
                    mt = MediaType.parse(payload.getAsJsonObject("headers").get("Content-Type").getAsString());
                }
                rb.method(upper, RequestBody.create(reqBody == null ? "" : reqBody,
                        mt != null ? mt : MediaType.parse("text/plain; charset=utf-8")));
            }

            try (Response resp = genericHttp.newCall(rb.build()).execute()) {
                ResponseBody respBody = resp.body();
                String text = respBody != null ? respBody.string() : "";
                boolean truncated = false;
                if (text.length() > HTTP_BODY_MAX) {
                    text = text.substring(0, HTTP_BODY_MAX);
                    truncated = true;
                }
                JsonObject p = new JsonObject();
                p.addProperty("status", resp.code());
                JsonObject respHeaders = new JsonObject();
                Set<String> names = resp.headers().names();
                for (String name : names) {
                    respHeaders.addProperty(name, resp.headers(name).toString());
                }
                p.add("headers", respHeaders);
                p.addProperty("body", text);
                p.addProperty("truncated", truncated);
                respondOk(reqId, p);
            }
        } catch (Exception e) {
            Log.w(TAG, "http 代理失败", e);
            respondError(reqId, 30, "HTTP 代理失败: " + e.getMessage());
        }
    }

    // ── 语音输入 vt_asr（系统 SpeechRecognizer，一次一个会话） ─────────────────

    /**
     * action:'vt_asr' —— 拉起一次系统语音识别，结果文本回传手环。
     * 成功响应 payload: {text:"…"}；失败 ok=false + 中文 message。
     * 必须在 worker 线程调用（SpeechRecognizer 依赖本线程 Looper 回调）。
     */
    private void startAsr(String reqId) {
        if (asrBusy) {
            respondError(reqId, 40, "语音识别进行中，请稍候");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            respondError(reqId, 41, "未获得录音权限：请先打开本App并允许麦克风权限");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            respondError(reqId, 42, "手机不支持语音识别");
            return;
        }
        asrBusy = true;
        asrReqId = reqId;
        try {
            asr = SpeechRecognizer.createSpeechRecognizer(this);
            asr.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] buf) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int event, Bundle b) {}
                @Override public void onPartialResults(Bundle b) {}
                @Override public void onError(int code) {
                    worker.post(() -> handleAsrError(code));
                }
                @Override public void onResults(Bundle b) {
                    worker.post(() -> handleAsrResults(b));
                }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            worker.postDelayed(asrTimeoutTask, ASR_TIMEOUT_MS);
            asr.startListening(intent);
        } catch (Exception e) {
            destroyAsr();
            respondError(reqId, 43, "语音识别初始化失败: " + e.getMessage());
        }
    }

    private void handleAsrResults(Bundle b) {
        String reqId = asrReqId;
        String text = null;
        if (b != null) {
            ArrayList<String> results = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (results != null && !results.isEmpty()) text = results.get(0);
        }
        destroyAsr();
        if (text == null || text.trim().isEmpty()) {
            respondError(reqId, 44, "未获取到语音，请重试");
        } else {
            JsonObject p = new JsonObject();
            p.addProperty("text", text);
            respondOk(reqId, p);
        }
    }

    private void handleAsrError(int code) {
        String msg;
        switch (code) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                msg = "未获得录音权限"; break;
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                msg = "未获取到语音，请重试"; break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                msg = "语音识别网络不可用"; break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                msg = "语音识别忙，请稍候"; break;
            default:
                msg = "语音识别失败(" + code + ")，请重试";
        }
        finishAsrError(msg);
    }

    private void finishAsrError(String message) {
        String reqId = asrReqId;
        destroyAsr();
        respondError(reqId, 45, message);
    }

    private void destroyAsr() {
        if (worker != null) worker.removeCallbacks(asrTimeoutTask);
        if (asr != null) {
            try { asr.cancel(); asr.destroy(); } catch (Exception ignore) {}
            asr = null;
        }
        asrBusy = false;
        asrReqId = null;
    }

    // ── 帧拼装 ─────────────────────────────────────────────────────────────

    private void respondOk(String id, JsonObject payload) {
        JsonObject frame = new JsonObject();
        frame.addProperty("v", 1);
        frame.addProperty("id", id);
        frame.addProperty("ok", true);
        frame.addProperty("code", 0);
        frame.addProperty("message", "");
        frame.add("payload", payload == null ? new JsonObject() : payload);
        channel.sendToWatch(frame.toString());
    }

    private void respondError(String id, int code, String message) {
        JsonObject frame = new JsonObject();
        frame.addProperty("v", 1);
        frame.addProperty("id", id);
        frame.addProperty("ok", false);
        frame.addProperty("code", code);
        frame.addProperty("message", message == null ? "" : message);
        frame.add("payload", new JsonObject());
        channel.sendToWatch(frame.toString());
    }

    private void sendProgress(String bookKey, int percent, long bytes, long total) {
        JsonObject p = new JsonObject();
        p.addProperty("bookKey", bookKey);
        p.addProperty("percent", percent);
        p.addProperty("bytes", bytes);
        p.addProperty("total", total);
        pushFrame("download_progress", p);
    }

    private void sendChunk(String bookKey, int seq, String chunk, boolean eof) {
        JsonObject p = new JsonObject();
        p.addProperty("bookKey", bookKey);
        p.addProperty("seq", seq);
        p.addProperty("chunk", chunk);
        p.addProperty("eof", eof);
        pushFrame("download_chunk", p);
    }

    private void pushFrame(String kind, JsonObject payload) {
        JsonObject frame = new JsonObject();
        frame.addProperty("v", 1);
        frame.addProperty("id", "push-" + (++pushFrameSeq));
        frame.addProperty("kind", kind);
        frame.add("payload", payload);
        channel.sendToWatch(frame.toString());
    }

    /** 精确统计一段字符的 UTF-8 字节数（用于 progress 的 bytes/total）。 */
    private static long utf8Length(char[] c, int off, int len) {
        long b = 0;
        int end = off + len;
        for (int i = off; i < end; i++) {
            char ch = c[i];
            if (ch < 0x80) b += 1;
            else if (ch < 0x800) b += 2;
            else if (Character.isSurrogate(ch)) { b += 4; i++; } // 代理对 4 字节，跳过下半个
            else b += 3;
        }
        return b;
    }

    // ── 前台通知 / 生命周期 ─────────────────────────────────────────────────

    private void startForegroundNotices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "摩柿桥接", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("摩柿小说桥接运行中")
                    .setContentText("保持手环与手机蓝牙连接，即可联网取书")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("摩柿小说桥接运行中")
                    .setContentText("保持手环与手机蓝牙连接")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .build();
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不需要绑定
    }

    @Override
    public void onDestroy() {
        destroyAsr();
        if (vtClient != null) vtClient.quit();
        if (channel != null) channel.close();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }
}
