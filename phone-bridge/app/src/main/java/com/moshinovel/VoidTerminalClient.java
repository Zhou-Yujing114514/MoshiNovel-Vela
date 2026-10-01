package com.moshinovel;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 虚空终端（buer.kdns.fr）聊天客户端。
 *
 * 背景：Vela QuickApp 无 WebSocket API，WSS 长连由本手机桥持有，手环经 interconnect 收发。
 * 字段级依据：spec/voidterminal-im.md；桥接动作字段依据：architecture.md §11.2。
 *
 * 职责：
 *   1) vtLogin：POST https://buer.kdns.fr/api/login {username,password} → {ok,token,user}；
 *      拿 token 后连 wss://buer.kdns.fr/ws，握手成功立即发 {"type":"auth","token":..,"lite":true}，
 *      等 hello（self/friends/groups/globalMsgs）。
 *   2) 维护会话表：大厅 roomKey="global"（服务端内部 id "public" 映射而来）+
 *      friends→"dm:<uid>" + groups→"group:<gid>"；每会话 lastMsg/unread。
 *   3) 收 global/dm/group 推送：dm 对端 peer = from==selfId ? to : from；
 *      fromName 缺省回退 from；经 Callback 上抛给 BridgeService。
 *   4) 出站：sendGlobal/sendGroup/sendDm → 对应 WSS 报文（不带 token/lite）。
 *   5) 保活：服务端 ping 回 pong；客户端 20s 发 WS ping；断线 5s 重连 + 重发 auth。
 *   6) 状态回调：connected / reconnecting / offline。
 *
 * TLS：走系统信任链（buer 在 Cloudflare 后面，边缘证书轮换，不硬 pin）。
 * 本类不持久化任何凭据；token 仅内存持有。
 */
public class VoidTerminalClient {

    private static final String TAG = "VoidTerminal";

    private static final String HTTP_BASE = "https://buer.kdns.fr";
    private static final String WSS_URL = "wss://buer.kdns.fr/ws";
    private static final long RECONNECT_DELAY_MS = 5000;
    private static final long HEARTBEAT_MS = 20000;
    private static final String PUBLIC_ROOM_NAME = "网站问题反馈区";

    /** 向 BridgeService 上抛事件（实现方负责切到协议 worker 线程）。 */
    public interface Callback {
        /** WSS 连接状态变化：connected / reconnecting / offline。 */
        void onStatus(String state);
        /** 收到一条新消息（global/group/dm 统一归一化后）。 */
        void onMessage(String type, String roomKey, String from, String fromName, String content);
    }

    static class Room {
        final String key;   // "global" | "group:<gid>" | "dm:<uid>"
        final String type;  // "global" | "group" | "dm"
        String name;
        String lastMsg = "";
        int unread = 0;

        Room(String key, String type, String name) {
            this.key = key;
            this.type = type;
            this.name = name;
        }
    }

    private final OkHttpClient http;
    private final Callback cb;
    private final HandlerThread ioThread;
    private final Handler io;

    private String token;
    private String selfId = "";
    private String selfName = "";

    private WebSocket ws;
    private volatile String state = "offline";
    private boolean quitting = false; // 手动 vt_quit 后抑制自动重连
    private final LinkedHashMap<String, Room> rooms = new LinkedHashMap<>();

    private final Runnable heartbeatTask = new Runnable() {
        @Override public void run() {
            if (ws != null && !quitting) {
                ws.send(ByteString.EMPTY); // WS 协议 ping 帧
                io.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    public VoidTerminalClient(Callback cb) {
        this.cb = cb;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // 长连，不读超时
                .build();
        this.ioThread = new HandlerThread("void-ws");
        this.ioThread.start();
        this.io = new Handler(ioThread.getLooper());
    }

    // ── 登录 + 建连 ─────────────────────────────────────────────────────────

    /**
     * HTTP 登录取 token，随后建立/复用 WSS 并发 auth。
     *
     * @return token
     * @throws IOException 登录失败（状态码/错误文案已带中文）
     */
    public synchronized String login(String username, String password) throws java.io.IOException {
        quitting = false;
        MediaType json = MediaType.get("application/json; charset=utf-8");
        String body = "{\"username\":\"" + escapeJson(username)
                + "\",\"password\":\"" + escapeJson(password) + "\"}";
        Request req = new Request.Builder()
                .url(HTTP_BASE + "/api/login")
                .post(RequestBody.create(body, json))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String text = rb != null ? rb.string() : "";
            if (!resp.isSuccessful()) {
                String hint = resp.code() == 401 ? "用户名或密码错误"
                        : resp.code() == 403 ? "账号已被封禁"
                        : resp.code() == 503 ? "服务维护中"
                        : "登录失败(HTTP " + resp.code() + ")";
                throw new java.io.IOException(hint);
            }
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (!o.has("token") || o.get("token").getAsString().isEmpty()) {
                throw new java.io.IOException("登录响应未返回 token");
            }
            this.token = o.get("token").getAsString();
            if (o.has("user") && o.get("user").isJsonObject()) {
                JsonObject u = o.getAsJsonObject("user");
                if (u.has("id")) selfId = u.get("id").getAsString();
                if (u.has("username")) selfName = u.get("username").getAsString();
            }
        }
        io.post(this::startWebSocket);
        return token;
    }

    private void startWebSocket() {
        if (quitting) return;
        updateState("reconnecting");
        Request req = new Request.Builder().url(WSS_URL).build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                // 握手成功立即 auth（重连后也自动重发）
                JsonObject auth = new JsonObject();
                auth.addProperty("type", "auth");
                auth.addProperty("token", token == null ? "" : token);
                auth.addProperty("lite", true);
                webSocket.send(auth.toString());
                updateState("connected");
                io.postDelayed(heartbeatTask, HEARTBEAT_MS);
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handleServerText(text);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                // 二进制帧不处理
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                onSocketDead();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.w(TAG, "WSS 连接失败: " + t.getMessage());
                onSocketDead();
            }
        });
    }

    private void onSocketDead() {
        io.removeCallbacks(heartbeatTask);
        ws = null;
        if (quitting) {
            updateState("offline");
            return;
        }
        updateState("reconnecting");
        io.postDelayed(this::startWebSocket, RECONNECT_DELAY_MS); // 断线 5s 重连
    }

    // ── 入站报文（仅认 hello/global/dm/group，其余按规格静默记录） ──────────────

    private void handleServerText(String text) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            switch (type) {
                case "hello":
                    handleHello(o);
                    break;
                case "global": {
                    String from = str(o, "from");
                    String fromName = strOrDefault(o, "fromName", from);
                    String content = str(o, "content");
                    onIncoming("global", "global", from, fromName, content);
                    break;
                }
                case "dm": {
                    String from = str(o, "from");
                    String to = str(o, "to");
                    String fromName = strOrDefault(o, "fromName", from);
                    String content = str(o, "content");
                    String peer = from.equals(selfId) ? to : from; // 对端 = 非自己的那一方
                    String roomKey = "dm:" + peer;
                    ensureRoom(roomKey, "dm", fromName);
                    onIncoming("dm", roomKey, from, fromName, content);
                    break;
                }
                case "group": {
                    String from = str(o, "from");
                    String gid = str(o, "gid");
                    String fromName = strOrDefault(o, "fromName", from);
                    String content = str(o, "content");
                    String roomKey = "group:" + gid;
                    ensureRoom(roomKey, "group", fromName);
                    onIncoming("group", roomKey, from, fromName, content);
                    break;
                }
                case "error":
                case "banned":
                case "maintenance":
                    // ESP 客户端不解析这三种；手机桥仅记录，靠断线重连兜底（见 im 规格 §8）。
                    Log.i(TAG, "server msg type=" + type + ": " + text);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "解析 WSS 报文失败: " + e.getMessage());
        }
    }

    private void handleHello(JsonObject o) {
        rooms.clear();
        rooms.put("global", new Room("global", "global", PUBLIC_ROOM_NAME));
        if (o.has("self") && o.get("self").isJsonObject()) {
            JsonObject s = o.getAsJsonObject("self");
            if (s.has("id")) selfId = s.get("id").getAsString();
            if (s.has("username")) selfName = s.get("username").getAsString();
        }
        if (o.has("friends") && o.get("friends").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("friends")) {
                JsonObject f = el.getAsJsonObject();
                String id = str(f, "id");
                String name = strOrDefault(f, "name", id);
                if (!id.isEmpty()) rooms.put("dm:" + id, new Room("dm:" + id, "dm", name));
            }
        }
        if (o.has("groups") && o.get("groups").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("groups")) {
                JsonObject g = el.getAsJsonObject();
                String id = str(g, "id");
                String name = strOrDefault(g, "name", id);
                if (!id.isEmpty()) rooms.put("group:" + id, new Room("group:" + id, "group", name));
            }
        }
        // lite 大厅最近 10 条：仅用作大厅 lastMsg 预览（历史翻页 v2 再做）
        if (o.has("globalMsgs") && o.get("globalMsgs").isJsonArray()) {
            JsonArray msgs = o.getAsJsonArray("globalMsgs");
            for (int i = 0; i < msgs.size(); i++) {
                JsonObject m = msgs.get(i).getAsJsonObject();
                String c = m.has("content") ? m.get("content").getAsString() : "";
                rooms.get("global").lastMsg = c;
            }
        }
        Log.i(TAG, "hello 就绪: self=" + selfName + " rooms=" + rooms.size());
    }

    private void onIncoming(String type, String roomKey, String from,
                             String fromName, String content) {
        Room r = rooms.get(roomKey);
        if (r != null) {
            r.lastMsg = content;
            r.unread++;
        }
        cb.onMessage(type, roomKey, from, fromName, content);
    }

    private Room ensureRoom(String key, String type, String name) {
        Room r = rooms.get(key);
        if (r == null) {
            r = new Room(key, type, name);
            rooms.put(key, r);
        } else if (name != null && !name.isEmpty()) {
            r.name = name;
        }
        return r;
    }

    // ── 出站 ───────────────────────────────────────────────────────────────

    /**
     * 发送消息（vt_send 转译）。
     *
     * @param type    global | group | dm
     * @param id      group 时=gid，dm 时=对方 uid；global 可空
     * @param content 文本（空串由手环侧拦截，这里再兜底）
     */
    public synchronized void send(String type, String id, String content) throws java.io.IOException {
        if (ws == null) throw new java.io.IOException("未连接虚空终端，请稍后重试");
        if (content == null || content.isEmpty()) throw new java.io.IOException("消息内容为空");
        JsonObject m = new JsonObject();
        switch (type) {
            case "global":
                m.addProperty("type", "global");
                m.addProperty("content", content);
                break;
            case "group":
                if (id == null || id.isEmpty()) throw new java.io.IOException("群聊缺少 gid");
                m.addProperty("type", "group");
                m.addProperty("gid", id);
                m.addProperty("content", content);
                break;
            case "dm":
                if (id == null || id.isEmpty()) throw new java.io.IOException("私聊缺少对方 uid");
                m.addProperty("type", "dm");
                m.addProperty("to", id);
                m.addProperty("content", content);
                break;
            default:
                throw new java.io.IOException("未知消息类型: " + type);
        }
        ws.send(m.toString());
    }

    /** vt_quit：手动断开，抑制自动重连。 */
    public synchronized void quit() {
        quitting = true;
        io.removeCallbacks(heartbeatTask);
        if (ws != null) {
            ws.close(1000, "bye");
            ws = null;
        }
        updateState("offline");
    }

    /** vt_rooms：组装 {self:{id,username}, rooms:[{key,type,name,lastMsg,unread}]}。 */
    public synchronized JsonObject roomsPayload() {
        JsonObject p = new JsonObject();
        JsonObject s = new JsonObject();
        s.addProperty("id", selfId == null ? "" : selfId);
        s.addProperty("username", selfName == null ? "" : selfName);
        p.add("self", s);
        JsonArray arr = new JsonArray();
        for (Room r : rooms.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", r.key);
            o.addProperty("type", r.type);
            o.addProperty("name", r.name);
            o.addProperty("lastMsg", r.lastMsg == null ? "" : r.lastMsg);
            o.addProperty("unread", r.unread);
            arr.add(o);
        }
        p.add("rooms", arr);
        return p;
    }

    public synchronized String getState() {
        return state;
    }

    private void updateState(String s) {
        if (!s.equals(state)) {
            state = s;
            Log.i(TAG, "vt state -> " + s);
            if (cb != null) cb.onStatus(s);
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String strOrDefault(JsonObject o, String k, String dflt) {
        String v = str(o, k);
        return v.isEmpty() ? dflt : v;
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
