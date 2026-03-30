package top.lingyuzhao.codeBookChatApp.utils;

import android.content.Context;
import android.util.Log;

import java.net.URISyntaxException;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import top.lingyuzhao.codeBookChatApp.AppConstants;
import top.lingyuzhao.codeBookChatApp.KeepAliveForegroundService;
import top.lingyuzhao.codeBookChatApp.push.PushNotifyTool;
import top.lingyuzhao.utils.StrUtils;

public class WsUtils {
    private static final String DOMAIN = AppConstants.WSS_BASE;
    private static final String DOMAIN_HTTP = AppConstants.CHAT_PAGE_URL;

    // ✅ 全局单例 OkHttpClient，线程池复用，无 pingInterval（服务端未实现 Pong）
    private static volatile OkHttpClient sharedClient;

    private static OkHttpClient getClient() {
        if (sharedClient == null) {
            synchronized (WsUtils.class) {
                if (sharedClient == null) {
                    sharedClient = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(0, TimeUnit.SECONDS)   // 长连接必须设为 0
                            .writeTimeout(10, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return sharedClient;
    }

    // ------------------------------------------------------------------ //
    //  全功能频道（首次连接，需要 token）
    // ------------------------------------------------------------------ //

    public static void createWebSocket(
            final String token,
            final long userId,
            final Context appContext,
            final KeepAliveForegroundService service) throws URISyntaxException {

        final String id = service.getSessionId() != null ? service.getSessionId() : "WS|" + System.currentTimeMillis();

        // ✅ 每个连接独立计数，消除静态变量竞争
        final AtomicInteger noReadCount = new AtomicInteger(0);

        Request request = new Request.Builder()
                .url(DOMAIN + "/ws/chat?token=" + token + "&noNeedOnline=true")
                .build();

        service.webSocketClient = getClient().newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(id, "全功能频道连接成功");
                webSocket.send("{\"command\": 6}"); // 拉取未读
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // 服务端收到心跳 "0" 后回传 "0"，刷新通知时间
                if ("0".equals(text)) {
                    KeepAliveForegroundService.requestUpdateNotificationText(appContext,
                            "上次探测：" + FastTimeFormatter.formatTimestampToHHMMSS(
                                    System.currentTimeMillis()));
                    return;
                }
                Log.i(id, "收到: " + text);
                PushNotifyTool.ParsedMessage msg = PushNotifyTool.ParsedMessage.fromJsonSafe(text);
                if (msg == null) return;
                switch (msg.getCommand()) {
                    case 0 -> {
                        if (msg.getRecId() == userId) {
                            PushNotifyTool.notifyFromWebSocketJson(appContext, msg);
                        }
                    }
                    case 2 -> {
                        PushNotifyTool.ParsedMessage last =
                                PushNotifyTool.ParsedMessage.fromJsonSafe(
                                        msg.getRawJson(), msg.getLastMessage());
                        PushNotifyTool.notifyFromWebSocketJson(appContext, last);
                    }
                    case 6 -> {
                        noReadCount.incrementAndGet();
                        if (msg.isLast()) {
                            PushNotifyTool.postNotification(appContext, false, "您有消息",
                                    "您收到了 " + noReadCount.get() + " 个好友的新消息！",
                                    DOMAIN_HTTP, AppConstants.LOGO_URL, "", (int) System.currentTimeMillis(),
                                    null);
                            noReadCount.set(0);
                        }
                    }
                    case 10 -> {
                        final boolean once = Boolean.parseBoolean(msg.getBody());
                        Log.i(id, "收到GPS的定位请求，客户端应开始处理！定位类型：" + (once ? "一次性定位" : "持续定位"));
                        // 调用定位器 让 MainActivity 定位
                        service.requestLocationFromActivity(once);
                    }
                    case 13 -> {
                        // 调用终止定位器的逻辑
                        Log.i(id, "收到GPS的定位终止，客户端应开始处理！");
                        service.closeLocationFromActivity();
                    }
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                // ✅ 网络错误 → 通知 Service 重连（走无 token 路径）
                Log.e(id, "连接失败: " + t.getMessage() + "; GPS的定位终止，因ws错误，客户端应开始处理！");
                service.closeLocationFromActivity();
                service.onWebSocketDisconnected("failure: " + t.getMessage());
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (code == 1000) {
                    Log.i(id, "正常关闭");
                    return;
                }
                // ✅ token 过期/异常关闭 → 交给 Service，后续统一走同步频道
                Log.w(id, "异常关闭 [" + code + "]: " + reason);
                service.onWebSocketDisconnected("closed: " + reason);
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  同步频道（无 token，断线重连专用）
    // ------------------------------------------------------------------ //

    public static void createWebSocket(
            final long userId,
            final Context appContext,
            final KeepAliveForegroundService service) throws URISyntaxException {

        final String id = "WS-sy|" + System.currentTimeMillis();
        service.setSessionId(id);

        // ✅ 改为局部变量，避免两个 WS 实例共享静态字段互相污染
        final PushNotifyTool.ParsedMessage newMsgTemplate = new PushNotifyTool.ParsedMessage(1, userId, 0, "收到了新消息", "频道", System.currentTimeMillis(), 0,
                true, AppConstants.CHAT_PAGE_URL, "", new Date().hashCode(), AppConstants.BASE_URL + "/image/logo.jpg", null);

        Request request = new Request.Builder()
                .url(DOMAIN + "/ws/synchronize?userId=" + userId)
                .build();

        service.webSocketClient = getClient().newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(id, "同步频道连接成功（轻量模式）");
                webSocket.send("6");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.i(id, "同步收到: " + text);
                try {
                    boolean notSure = text.charAt(text.length() - 1) == '?';
                    final String[] strings = StrUtils.splitBy(notSure ? text.substring(0, text.length() - 1) : text, '&', 2);
                    // 同步频道的参数格式是 command&参数?（?代表不确定的，可以不携带？）
                    int command = Integer.parseInt(strings[0]);
                    switch (command) {
                        case 0 ->
                                KeepAliveForegroundService.requestUpdateNotificationText(appContext,
                                        "上次同步：" + FastTimeFormatter.formatTimestampToHHMMSS(
                                                System.currentTimeMillis()));
                        case 1 -> {
                            newMsgTemplate.setTs(System.currentTimeMillis());
                            PushNotifyTool.notifyFromWebSocketJson(appContext, newMsgTemplate);
                        }
                        case 6 -> {
                            if (strings.length < 2) {
                                Log.w(id, "command=6 解析失败");
                                return;
                            }
                            PushNotifyTool.postNotification(appContext, false, "您有消息",
                                    "您收到了 " + strings[1] + " 个好友的新消息！",
                                    DOMAIN_HTTP, AppConstants.LOGO_URL, "", (int) System.currentTimeMillis(),
                                    null);
                        }
                    }
                } catch (NumberFormatException e) {
                    Log.w(id, "非预期格式，忽略: " + text);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(id, "同步频道失败: " + t.getMessage());
                service.onWebSocketDisconnected("sync_failure: " + t.getMessage());
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (code == 1000) {
                    Log.i(id, "同步频道正常关闭");
                    return;
                }
                Log.w(id, "同步频道异常关闭 [" + code + "]: " + reason);
                service.onWebSocketDisconnected("sync_closed: " + reason);
            }
        });
    }
}