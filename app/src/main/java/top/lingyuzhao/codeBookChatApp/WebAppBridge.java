package top.lingyuzhao.codeBookChatApp;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;

import com.alibaba.fastjson2.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

public class WebAppBridge {

    private static final String TAG = "WebViewJSInterface";

    private final Context appContext;

    // ✅ 防止重复启动 GPS（核心）
    private final AtomicBoolean gpsScheduled = new AtomicBoolean(false);

    // 主线程 Handler
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public WebAppBridge(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @JavascriptInterface
    public void notify(String messageJson) {
        Log.d(TAG, "收到原生JSON: " + messageJson);

        final JSONObject parse = JSONObject.parse(messageJson);
        final String sessionId = parse.getString("sessionId");
        final int command = parse.getIntValue("command");

        switch (command) {

            case 5:
                handleCommand5(parse, sessionId);
                break;

            case 10:
                handleCommand10(parse);
                break;

            case 12:
                handleCommand12(sessionId);
                break;

            case 13:
                handleCommand13();
                break;

            default:
                Log.i(TAG, "未知的 command 字段：" + parse);
        }
    }

    /**
     * command=5：启动前台服务 + 自动调度一次 GPS
     */
    private void handleCommand5(JSONObject parse, String sessionId) {
        String token = parse.getString("html");

        if (token == null || token.isEmpty()) {
            return;
        }

        final long recId = parse.getLongValue("recId");

        Log.d(TAG, "收到 token，启动前台 Service，userId=" + recId);

        Intent intent = new Intent(appContext, KeepAliveForegroundService.class);
        intent.putExtra(KeepAliveForegroundService.intentCommandKey, 5);
        intent.putExtra(KeepAliveForegroundService.EXTRA_WS_TOKEN, token);
        intent.putExtra(KeepAliveForegroundService.EXTRA_NOTIFICATION_TEXT, "连接成功！");
        intent.putExtra("userId", recId);
        intent.putExtra("sessionId", sessionId);

        appContext.startForegroundService(intent);

        // ✅ ⭐ 核心改动：由原生调度 GPS（代替 JS setTimeout）
        scheduleGpsOnceIfNeeded();
    }

    /**
     * 延迟启动一次 GPS（防重复）
     */
    private void scheduleGpsOnceIfNeeded() {
        if (!gpsScheduled.compareAndSet(false, true)) {
            Log.d(TAG, "GPS 已经调度过，忽略重复请求");
            return;
        }

        Log.d(TAG, "5秒后自动触发 GPS 定位");

        mainHandler.postDelayed(() -> {
            try {
                Intent gpsIntent = new Intent(appContext, KeepAliveForegroundService.class);
                gpsIntent.putExtra(KeepAliveForegroundService.intentCommandKey, 10);
                gpsIntent.putExtra(KeepAliveForegroundService.RUN_GPS_ONCE, 1);

                appContext.startForegroundService(gpsIntent);

                Log.d(TAG, "已触发一次 GPS 定位（command=10）");

            } catch (Exception e) {
                Log.e(TAG, "触发 GPS 失败", e);
                gpsScheduled.set(false); // 失败允许重试
            }
        }, 5000);
    }

    /**
     * command=10：JS 主动触发 GPS（仍然支持）
     */
    private void handleCommand10(JSONObject parse) {
        Intent intent = new Intent(appContext, KeepAliveForegroundService.class);
        intent.putExtra(KeepAliveForegroundService.intentCommandKey, 10);
        intent.putExtra(
                KeepAliveForegroundService.RUN_GPS_ONCE,
                parse.getBooleanValue("once") ? 1 : 0
        );

        appContext.startForegroundService(intent);
    }

    /**
     * command=12：更新 sessionId
     */
    private void handleCommand12(String sessionId) {
        Intent intent = new Intent(appContext, KeepAliveForegroundService.class);
        intent.putExtra(KeepAliveForegroundService.intentCommandKey, 12);
        intent.putExtra("sessionId", sessionId);

        appContext.startForegroundService(intent);
    }

    /**
     * command=13：停止 GPS
     */
    private void handleCommand13() {
        Intent intent = new Intent(appContext, KeepAliveForegroundService.class);
        intent.putExtra(KeepAliveForegroundService.intentCommandKey, 13);
        intent.putExtra(KeepAliveForegroundService.STOP_GPS_ONCE, true);

        appContext.startForegroundService(intent);

        // ✅ 允许下次重新调度
        gpsScheduled.set(false);
    }
}