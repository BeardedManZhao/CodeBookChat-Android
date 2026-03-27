package top.lingyuzhao.codeBookChatApp;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.net.URISyntaxException;

import okhttp3.WebSocket;
import top.lingyuzhao.codeBookChatApp.push.PushNotifyTool;
import top.lingyuzhao.codeBookChatApp.utils.WsUtils;

public class KeepAliveForegroundService extends Service {

    public static final String EXTRA_WS_TOKEN = "extra_ws_token";
    public static final String EXTRA_NOTIFICATION_TEXT = "extra_notification_text";
    public static final String CHANNEL_ID = "keep_alive_channel";

    private static final String TAG = "KeepAliveSvc";
    private static final int NOTIFICATION_ID = 0x1;
    private static final long HEARTBEAT_INTERVAL = 25_000L;  // 25秒
    private static final long RECONNECT_BASE = 3_000L;   // 首次重连等待
    private static final long RECONNECT_MAX = 60_000L;  // 最长退避
    private static final int WAKELOCK_TIMEOUT = 10 * 60 * 1000; // 10分钟
    private static boolean isDestroyed = false;
    // 对外暴露，供 WsUtils 回调写入
    public WebSocket webSocketClient = null;
    public String wsId = TAG;
    private HandlerThread handlerThread;
    private Handler wsHandler;
    private PowerManager.WakeLock wakeLock;
    // ✅ 只存 userId，token 仅首次使用，重连不再需要
    private long currentUserId = 0;
    private long reconnectDelay = RECONNECT_BASE;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isAllowReConnect = false;

    private String currentNotificationText = "连接中…";

    // ------------------------------------------------------------------ //
    //  生命周期
    // ------------------------------------------------------------------ //

    public KeepAliveForegroundService() {

    }

    public static boolean isDestroyed() {
        return isDestroyed;
    }

    /**
     * 任意位置调用，更新常驻通知文字
     */
    public static void requestUpdateNotificationText(Context context, String text) {
        Intent intent = new Intent("ACTION_UPDATE_NOTIFICATION").setPackage(AppConstants.PACKAGE_NAME);
        intent.putExtra(EXTRA_NOTIFICATION_TEXT, text);
        context.sendBroadcast(intent);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void onCreate() {
        super.onCreate();
        isDestroyed = false;
        ensureChannel();

        // 工作线程：所有 WS 操作都在这里，避免并发问题
        handlerThread = new HandlerThread("WsWorker");
        handlerThread.start();
        wsHandler = new Handler(handlerThread.getLooper());

        // WakeLock 带超时防泄漏，每次心跳刷新
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CodeBookChat::WsLock");
        wakeLock.setReferenceCounted(false);
        acquireWakeLock();

        // 注册网络重启回调
        registerNetworkCallback();
        // 注册通知接收器
        BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String text = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT);
                if (text != null) {
                    updateNotificationText(text);
                }
            }
        };
        registerReceiver(notificationReceiver,
                new IntentFilter("ACTION_UPDATE_NOTIFICATION"),
                RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ✅ 第一步先升前台，防止 ANR
        startForegroundCompat();

        if (intent != null) {
            // 更新通知文字
            String text = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT);
            if (text != null && !text.isEmpty()) {
                updateNotificationText(text);
            }

            // ✅ 只在首次收到 token 时用 token 连接，之后重连不走这里
            String token = intent.getStringExtra(EXTRA_WS_TOKEN);
            long userId = intent.getLongExtra("userId", 0);
            Log.i("KeepAliveForegroundService.onStartCommand", "为用户【" + userId + "】的全功能通道做准备！" + ";token=" + token);
            if (token != null && !token.isEmpty() && userId != 0) {
                currentUserId = userId;
                // 首次用 token 建立全功能频道
                wsHandler.post(() -> doConnectWithToken(token, userId));
            } else {
                Log.i("KeepAliveForegroundService.onStartCommand", "全功能通道未能建立，因为缺失关键数据！");
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户划掉 App 后，用 AlarmManager 1 秒后重拉服务
        Log.w(TAG, "任务被划掉，计划重启");
        Intent restart = new Intent(getApplicationContext(), KeepAliveForegroundService.class);
        restart.putExtra("userId", currentUserId);
        PendingIntent pi = PendingIntent.getService(
                getApplicationContext(), 1, restart,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1_000, pi);
        }
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        super.onDestroy();
        unregisterNetworkCallback();
        wsHandler.removeCallbacksAndMessages(null);
        wsHandler.post(() -> closeWebSocket("destroy"));
        handlerThread.quitSafely();
        releaseWakeLock();
    }

    // ------------------------------------------------------------------ //
    //  连接逻辑
    // ------------------------------------------------------------------ //

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 首次连接：使用 token，建立全功能频道
     */
    private void doConnectWithToken(String token, long userId) {
        closeWebSocket("new_token_connect");
        try {
            WsUtils.createWebSocket(token, userId, getApplicationContext(), this);
            reconnectDelay = RECONNECT_BASE;
            scheduleHeartbeat();
            Log.i(TAG, "全功能频道已连接");
        } catch (URISyntaxException e) {
            Log.e(TAG, "URI 错误", e);
            scheduleReconnect();
        }
    }

    /**
     * ✅ 断线重连：永远走无 token 的同步频道，不依赖过期 token
     */
    private void doConnectSync() {
        if (currentUserId == 0) {
            Log.w(TAG, "userId=0，跳过重连");
            return;
        }
        closeWebSocket("reconnect");
        try {
            WsUtils.createWebSocket(currentUserId, getApplicationContext(), this);
            reconnectDelay = RECONNECT_BASE;
            scheduleHeartbeat();
            Log.i(TAG, "同步频道已连接");
        } catch (URISyntaxException e) {
            Log.e(TAG, "URI 错误", e);
            scheduleReconnect();
        }
    }

    /**
     * 外部（WsUtils 回调）调用：通知 Service 连接已断，触发重连
     */
    public void onWebSocketDisconnected(String reason) {
        Log.w(TAG, "WS 断开: " + reason + "，" + reconnectDelay + "ms 后重连");
        wsHandler.removeCallbacksAndMessages("heartbeat");
        webSocketClient = null;
        scheduleReconnect();
    }

    // ------------------------------------------------------------------ //
    //  心跳（文本 "0"，无需服务端改动）
    // ------------------------------------------------------------------ //

    private void scheduleReconnect() {
        if (isDestroyed) return;
        wsHandler.removeCallbacksAndMessages("connect");
        if (webSocketClient == null) {
            wsHandler.postAtTime(() -> {
                if (!isDestroyed) doConnectSync();
            }, "connect", SystemClock.uptimeMillis() + reconnectDelay);
        }
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX);
    }

    private void scheduleHeartbeat() {
        wsHandler.removeCallbacksAndMessages("heartbeat");
        wsHandler.postAtTime(heartbeatTask, "heartbeat",
                SystemClock.uptimeMillis() + HEARTBEAT_INTERVAL);
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                if (!isAllowReConnect) {
                    Log.i(TAG, "网络初始化完成");
                    isAllowReConnect = true;
                    return;
                }
                Log.i(TAG, "网络恢复，立即重连");
                reconnectDelay = RECONNECT_BASE;
                wsHandler.removeCallbacksAndMessages("connect");
                if (webSocketClient == null) {
                    wsHandler.postAtTime(() -> {
                        if (!isDestroyed) doConnectSync();
                    }, "connect", SystemClock.uptimeMillis() + 500);
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.w(TAG, "网络断开，暂停心跳");
                wsHandler.removeCallbacksAndMessages("heartbeat");
            }
        };
        try {
            connectivityManager.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(),
                    networkCallback);
        } catch (Throwable t) {
            Log.w(TAG, "NetworkCallback 注册失败", t);
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignored) {
            }
        }
    }    private final Runnable heartbeatTask = () -> {
        if (isDestroyed) return;
        if (webSocketClient != null && webSocketClient.send("0")) {
            Log.d(wsId, "心跳 ✓");
            acquireWakeLock(); // 刷新 WakeLock 超时
            scheduleHeartbeat();
        } else {
            Log.w(wsId, "心跳失败，触发重连");
            onWebSocketDisconnected("heartbeat_failed");
        }
    };

    // ------------------------------------------------------------------ //
    //  网络变化监听：恢复联网后立即重连
    // ------------------------------------------------------------------ //

    private void acquireWakeLock() {
        try {
            if (!wakeLock.isHeld()) wakeLock.acquire(WAKELOCK_TIMEOUT);
        } catch (Throwable t) {
            Log.w(TAG, "WakeLock acquire 失败", t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ //
    //  WakeLock
    // ------------------------------------------------------------------ //

    private void closeWebSocket(String reason) {
        if (webSocketClient != null) {
            try {
                webSocketClient.close(1000, reason);
            } catch (Throwable ignored) {
            }
            webSocketClient = null;
        }
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification().build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification().build());
        }
    }

    // ------------------------------------------------------------------ //
    //  工具方法
    // ------------------------------------------------------------------ //

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保持码本频道在后台运行，便于接收新消息");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private NotificationCompat.Builder buildNotification() {
        PendingIntent pending = PendingIntent.getActivity(this, 0,
                PushNotifyTool.buildOpenIntent(this, false, null, null),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.foreground_notification_title))
                .setContentText(currentNotificationText)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW);
    }

    private void updateNotificationText(String text) {
        currentNotificationText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification().build());
    }




}