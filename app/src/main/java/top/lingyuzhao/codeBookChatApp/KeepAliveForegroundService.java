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
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
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

import java.lang.ref.WeakReference;
import java.net.URISyntaxException;

import okhttp3.WebSocket;
import top.lingyuzhao.codeBookChatApp.push.PushNotifyTool;
import top.lingyuzhao.codeBookChatApp.utils.WsUtils;

public class KeepAliveForegroundService extends Service {

    // ------------------------------------------------------------------ //
    //  常量
    // ------------------------------------------------------------------ //

    public static final String EXTRA_WS_TOKEN = "extra_ws_token";
    public static final String EXTRA_NOTIFICATION_TEXT = "extra_notification_text";
    public static final String CHANNEL_ID = "keep_alive_channel";
    public static final String intentCommandKey = "command";
    /**
     * 数值类型 是否启动GPS
     * <p>
     * -1 代表不操作 也是默认值
     * 1 代表启动GPS 一次性
     * 0 代表启动GPS 实时
     */
    public static final String RUN_GPS_ONCE = "RUN_GPS_ONCE";
    /**
     * 布尔类型 是否关闭 GPS
     * false 代表不操作 也是默认值
     * true  代表关闭 GPS 定位
     */
    public static final String STOP_GPS_ONCE = "STOP_GPS_ONCE";
    private static final String TAG = "KeepAliveSvc";
    private static final int NOTIFICATION_ID = 0x1;
    private static final long HEARTBEAT_INTERVAL = 25_000L;  // 25 秒
    private static final long RECONNECT_BASE = 3_000L;        // 首次重连等待
    private static final long RECONNECT_MAX = 60_000L;        // 最长退避
    private static final int WAKELOCK_TIMEOUT = 10 * 60 * 1000; // 10 分钟

    private static boolean isDestroyed = false;

    // ------------------------------------------------------------------ //
    //  Binder（供 Activity 通过 bindService 拿到 Service 实例）
    // ------------------------------------------------------------------ //
    private final IBinder binder = new LocalBinder();
    private final String thisHash = String.valueOf(this.hashCode());
    /**
     * 对外暴露，供 WsUtils 回调写入
     */
    public WebSocket webSocketClient = null;
    // ------------------------------------------------------------------ //
    //  字段
    // ------------------------------------------------------------------ //
    private String wsId = TAG;
    private HandlerThread handlerThread;
    private Handler wsHandler;
    private PowerManager.WakeLock wakeLock;
    /**
     * 只存 userId，token 仅首次使用，重连不再需要
     */
    private long currentUserId = 0;
    private long reconnectDelay = RECONNECT_BASE;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isAllowReConnect = false;
    private String currentNotificationText = "连接中…";
    /**
     * 弱引用，防止内存泄漏
     */
    private WeakReference<LocationRequestCallback> callbackRef;

    public static boolean isDestroyed() {
        return isDestroyed;
    }

    // ------------------------------------------------------------------ //
    //  静态工具方法
    // ------------------------------------------------------------------ //

    /**
     * 任意位置调用，更新常驻通知文字。
     */
    public static void requestUpdateNotificationText(Context context, String text) {
        Intent intent = new Intent("ACTION_UPDATE_NOTIFICATION")
                .setPackage(AppConstants.PACKAGE_NAME);
        intent.putExtra(EXTRA_NOTIFICATION_TEXT, text);
        context.sendBroadcast(intent);
    }

    /**
     * 设置定位请求回调，Activity 绑定成功后立即调用。
     */
    public void setLocationRequestCallback(LocationRequestCallback callback) {
        callbackRef = new WeakReference<>(callback);
    }

    // ------------------------------------------------------------------ //
    //  回调注册（供绑定后的 Activity 调用）
    // ------------------------------------------------------------------ //

    /**
     * 当需要让 MainActivity 定位时调用此方法，通过回调通知 Activity。
     */
    public void requestLocationFromActivity(boolean once) {
        LocationRequestCallback callback = callbackRef != null ? callbackRef.get() : null;
        if (callback != null) {
            callback.onLocationRequested(once, !once); // 如果 不是一次性的 就需要自动请求权限
        } else {
            Log.w(TAG, "requestLocationFromActivity：回调已释放，无法触发定位");
        }
    }

    public void closeLocationFromActivity() {
        LocationRequestCallback callback = callbackRef != null ? callbackRef.get() : null;
        if (callback != null) {
            callback.onLocationClose();
        } else {
            Log.w(TAG, "closeLocationFromActivity：回调已释放，无法触发定位");
        }
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

        // 注册网络恢复回调
        registerNetworkCallback();

        // 注册通知文字更新广播
        BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String text = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT);
                if (text != null) updateNotificationText(text);
            }
        };
        registerReceiver(notificationReceiver,
                new IntentFilter("ACTION_UPDATE_NOTIFICATION"),
                RECEIVER_NOT_EXPORTED);
    }


    // ------------------------------------------------------------------ //
    //  生命周期
    // ------------------------------------------------------------------ //

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 第一步先升前台，防止 ANR
        startForegroundCompat();

        if (intent != null) {
            switch (intent.getIntExtra(intentCommandKey, -1)) {
                case 5 -> {
                    // 只在首次收到 token 时用 token 连接，之后重连不走这里
                    String token = intent.getStringExtra(EXTRA_WS_TOKEN);
                    long userId = intent.getLongExtra("userId", 0);
                    if (token != null && !token.isEmpty() && userId != 0) {
                        Log.i(TAG, "为用户【" + userId + "】的全功能通道做准备！token=" + token);
                        currentUserId = userId;
                        wsHandler.post(() -> doConnectWithToken(token, userId));
                    } else {
                        Log.i(TAG, "全功能通道未能建立，缺失关键数据！");
                    }
                }
                case 10 -> {
                    // RUN_GPS_ONCE 检测
                    final int RUN_GPS_ONCE_VALUE = intent.getIntExtra(RUN_GPS_ONCE, -1);
                    if (RUN_GPS_ONCE_VALUE != -1) {
                        Log.i(TAG, "收到GPS启动命令! 是否一次性 = " + RUN_GPS_ONCE_VALUE);
                        // 代表要启动GPS
                        this.requestLocationFromActivity(RUN_GPS_ONCE_VALUE == 1);
                    } else {
                        Log.i(TAG, "收到GPS保持不变，once = " + RUN_GPS_ONCE_VALUE);
                    }
                }
                case 12 -> {
                    String text = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT);
                    if (text != null && !text.isEmpty()) updateNotificationText(text);
                    String sessionId = intent.getStringExtra("sessionId");
                    if (sessionId != null) {
                        // 代表是回传的 sessionId
                        Log.i(sessionId, "收到新的ID，开始变更ID为：" + sessionId);
                        setSessionId(sessionId);
                    }
                }
                case 13 -> {
                    // STOP_GPS_ONCE 检测
                    if (intent.getBooleanExtra(STOP_GPS_ONCE, false)) {
                        Log.i(TAG, "收到关闭 GPS 命令");
                        // 代表要关闭GPS
                        this.closeLocationFromActivity();
                    }
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 返回 binder 供 Activity 通过 bindService 获取 Service 实例
        return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
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

    /**
     * Activity 定位成功后，通过 Binder 调用此方法将坐标传回 Service。
     *
     * @param latitude    纬度
     * @param longitude   经度
     * @param rawLocation 原始 Location 对象（可为 null）
     */
    public void onLocationResult(double latitude, double longitude, Location rawLocation) {
        Log.d(TAG, "收到位置回传");

        if (webSocketClient == null) {
            Log.w(TAG, "onLocationResult：WebSocket 尚未连接，丢弃本次位置");
            return;
        }

        // 广播
        final com.alibaba.fastjson2.JSONObject json = new com.alibaba.fastjson2.JSONObject();
        json.put("command", 11);
        json.put("lat", latitude);
        json.put("lng", longitude);
        json.put("clientId", thisHash);
        webSocketClient.send(json.toString());
    }

    // ------------------------------------------------------------------ //
    //  定位结果回传（Activity 定位成功后调用此方法）
    // ------------------------------------------------------------------ //

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

    // ------------------------------------------------------------------ //
    //  连接逻辑
    // ------------------------------------------------------------------ //

    /**
     * 断线重连：走无 token 的同步频道，不依赖过期 token
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

    // ------------------------------------------------------------------ //
    //  心跳
    // ------------------------------------------------------------------ //

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
    }

    private void acquireWakeLock() {
        try {
            if (!wakeLock.isHeld()) wakeLock.acquire(WAKELOCK_TIMEOUT);
        } catch (Throwable t) {
            Log.w(TAG, "WakeLock acquire 失败", t);
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
    //  网络变化监听
    // ------------------------------------------------------------------ //

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
    }

    private void closeWebSocket(String reason) {
        if (webSocketClient != null) {
            try {
                webSocketClient.close(1000, reason);
            } catch (Throwable ignored) {
            }
            webSocketClient = null;
        }
    }

    // ------------------------------------------------------------------ //
    //  WakeLock
    // ------------------------------------------------------------------ //

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification().build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification().build());
        }
    }

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

    // ------------------------------------------------------------------ //
    //  WebSocket 工具
    // ------------------------------------------------------------------ //

    private NotificationCompat.Builder buildNotification() {
        PendingIntent pending = PendingIntent.getActivity(this, 0,
                PushNotifyTool.buildOpenIntent(this, false, null, null),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.notif_message)
                .setContentTitle(getString(R.string.foreground_notification_title))
                .setContentText(currentNotificationText)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW);
    }

    // ------------------------------------------------------------------ //
    //  通知
    // ------------------------------------------------------------------ //

    private void updateNotificationText(String text) {
        currentNotificationText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification().build());
    }

    public String getSessionId() {
        return wsId;
    }

    public void setSessionId(String wsId) {
        this.wsId = wsId;
    }

    /**
     * 绑定凭证。Activity 通过 {@link ServiceConnection#onServiceConnected} 拿到此对象，
     * 再调用 {@link #getService()} 即可直接操作 Service。
     */
    public class LocalBinder extends Binder {
        public KeepAliveForegroundService getService() {
            return KeepAliveForegroundService.this;
        }
    }

    // ------------------------------------------------------------------ //
    //  getter / setter
    // ------------------------------------------------------------------ //




}