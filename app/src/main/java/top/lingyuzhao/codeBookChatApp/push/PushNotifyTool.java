package top.lingyuzhao.codeBookChatApp.push;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONObject;
import top.lingyuzhao.codeBookChatApp.AppConstants;
import top.lingyuzhao.codeBookChatApp.MainActivity;
import top.lingyuzhao.codeBookChatApp.R;
import top.lingyuzhao.codeBookChatApp.utils.HtmlTextExtractor;
import top.lingyuzhao.utils.CacheUtils;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class PushNotifyTool {
    public static final String CHANNEL_ID_CHAT = "chat_message";
    public static final String EXTRA_OPEN_URL = "extra_open_url";
    public static final String EXTRA_MESSAGE_JSON = "extra_message_json";
    // 简单的用户姓名缓存：userId -> userName
    private static final CacheUtils USER_NAME_CACHE = CacheUtils.getCacheUtils("用户姓名缓存", 600000);

    private PushNotifyTool() {
    }

    public static void ensureChatChannel(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID_CHAT);
        if (existing != null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID_CHAT,
                "聊天消息",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("来自聊天 WebSocket 的消息通知");
        nm.createNotificationChannel(channel);
    }

    public static boolean hasPostNotificationsPermission(Context context) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void notifyFromWebSocketJson(Context context, ParsedMessage parsed) {
        ensureChatChannel(context);
        if (!hasPostNotificationsPermission(context)) return;

        // 所有网络操作放到后台线程，避免阻塞 JS / UI
        Context appCtx = context.getApplicationContext();
        new Thread(() -> {
            String senderName = null;
            Bitmap largeIcon = null;
            try {
                senderName = getOrFetchUserName(parsed.sendId);
                if (parsed.avatarUrl != null && !parsed.avatarUrl.isEmpty()) {
                    largeIcon = fetchBitmap(parsed.avatarUrl);
                }
            } catch (Throwable ignored) {
            }
            postNotification(appCtx, parsed, new ExtraInfo(senderName, largeIcon));
        }, "notify-resolve-user").start();
    }

    public static void postNotification(Context context, String title, String body, String openUrl, String json, int notificationId, @Nullable ExtraInfo extra) {
        if (title == null || title.isEmpty()) {
            String name = extra != null ? extra.senderName : null;
            if (name != null && !name.isEmpty()) {
                title = "【" + name + "】的消息";
            } else {
                title = "新消息";
            }
        }

        if (body == null || body.isEmpty()) body = "你有一条新消息";

        Intent intent = buildOpenIntent(context, openUrl, json);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, notificationId, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID_CHAT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        if (extra != null && extra.largeIcon != null) {
            builder.setLargeIcon(extra.largeIcon);
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        NotificationManagerCompat.from(context).notify(notificationId, builder.build());
    }

    private static void postNotification(Context context, ParsedMessage msg, @Nullable ExtraInfo extra) {
        ensureChatChannel(context);
        if (!hasPostNotificationsPermission(context)) return;
        postNotification(
                context, msg.title, msg.body, msg.openUrl, msg.rawJson, msg.notificationId, extra
        );
    }

    public static Intent buildOpenIntent(Context context, @Nullable String openUrl, @Nullable String rawJson) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (openUrl != null && !openUrl.isEmpty()) intent.putExtra(EXTRA_OPEN_URL, openUrl);
        if (rawJson != null && !rawJson.isEmpty()) intent.putExtra(EXTRA_MESSAGE_JSON, rawJson);
        return intent;
    }

    private static String fetchUserName(long userId) throws Exception {
        URL url = new URL(AppConstants.BASE_URL + "/api/user/" + userId);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");

        try (InputStream in = conn.getInputStream()) {
            byte[] bytes = readAllBytes(in);
            String json = new String(bytes, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;
            return data.optString("userName", null);
        }
    }

    private static String getOrFetchUserName(long userId) {
        if (userId <= 0) return null;
        Object cached0 = USER_NAME_CACHE.get(String.valueOf(userId));
        if (cached0 != null) {
            final String cached = cached0.toString();
            if (!cached.isEmpty()) return cached;
        }
        try {
            String name = fetchUserName(userId);
            if (name != null && !name.isEmpty()) {
                USER_NAME_CACHE.put(String.valueOf(userId), name);
            }
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap fetchBitmap(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        conn.connect();
        try (InputStream in = conn.getInputStream()) {
            return BitmapFactory.decodeStream(in);
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }


    public static final class ExtraInfo {
        public final static ExtraInfo EXTRA_INFO_NULL = new ExtraInfo("系统", null);
        final String senderName;
        final Bitmap largeIcon;

        ExtraInfo(String senderName, Bitmap largeIcon) {
            this.senderName = senderName;
            this.largeIcon = largeIcon;
        }
    }

    public static final class ParsedMessage {
        final long sendId;
        final long recId;
        final String body;
        final String title;
        final int command;
        final boolean last;
        final String openUrl;
        final String rawJson;
        final int notificationId;
        final @Nullable String avatarUrl;
        final JSONObject lastMessage;
        long ts;

        public ParsedMessage(long sendId, long recId, String body, String title, long ts, int command, boolean last,
                              String openUrl, String rawJson, int notificationId, @Nullable String avatarUrl, JSONObject lastMessage) {
            this.sendId = sendId;
            this.recId = recId;
            this.body = body;
            this.title = title;
            this.ts = ts;
            this.command = command;
            this.last = last;
            this.openUrl = openUrl;
            this.rawJson = rawJson;
            this.notificationId = notificationId;
            this.avatarUrl = avatarUrl;
            this.lastMessage = lastMessage;
        }

        public static @Nullable ParsedMessage fromJsonSafe(String raw) {
            try {
                JSONObject obj = new JSONObject(raw);
                return fromJsonSafe(raw, obj);
            } catch (Throwable ignored) {
                return null;
            }
        }

        public static ParsedMessage fromJsonSafe(String raw, JSONObject obj) {
            long sendId = obj.optLong("sendId", 0);
            long recId = obj.optLong("recId", 0);
            String html = obj.optString("html", "");
            long ts = obj.optLong("ts", System.currentTimeMillis());
            int command = obj.optInt("command", 0);
            boolean last = obj.optBoolean("last", false);

            // 兼容服务端直接给 app 的格式（可选字段）
            String title = obj.optString("title", null);
            String body = obj.optString("body", null);
            if (body == null || body.isEmpty()) body = html;

            // 去掉 HTML 标签，避免通知内容过乱
            body = stripHtml(body);

            String openUrl = obj.optString("openUrl", null);
            if (openUrl == null || openUrl.isEmpty()) {
                // 默认回到聊天页；如果你想跳到具体会话，可以在网页/服务端传 openUrl
                openUrl = AppConstants.CHAT_PAGE_URL;
            }

            String avatarUrl = obj.optString("avatarUrl", null);
            if (avatarUrl == null || avatarUrl.startsWith("/")) {
                avatarUrl = AppConstants.BASE_URL + avatarUrl;
            }

            int notificationId = obj.has("notificationId")
                    ? obj.optInt("notificationId")
                    : stableNotificationId(sendId, recId, ts, html);

            // 查看是否存在 lastMessage
            JSONObject lastMessage = obj.optJSONObject("lastMessage");
            return new ParsedMessage(sendId, recId, body, title, ts, command, last, openUrl, raw, notificationId, avatarUrl, lastMessage);
        }

        private static int stableNotificationId(long sendId, long recId, long ts, String html) {
            int h = 17;
            h = 31 * h + (int) (sendId ^ (sendId >>> 32));
            h = 31 * h + (int) (recId ^ (recId >>> 32));
            h = 31 * h + (int) (ts ^ (ts >>> 32));
            h = 31 * h + (html == null ? 0 : html.hashCode());
            return h;
        }

        private static String stripHtml(String input) {
            return HtmlTextExtractor.extractBottomInnerText(input);
        }

        public long getSendId() {
            return sendId;
        }

        public long getRecId() {
            return recId;
        }

        public String getBody() {
            return body;
        }

        public String getTitle() {
            return title;
        }

        public long getTs() {
            return ts;
        }

        public void setTs(long timeMs) {
            this.ts = timeMs;
        }

        public int getCommand() {
            return command;
        }

        public boolean isLast() {
            return last;
        }

        public String getOpenUrl() {
            return openUrl;
        }

        public String getRawJson() {
            return rawJson;
        }

        public int getNotificationId() {
            return notificationId;
        }

        @Nullable
        public String getAvatarUrl() {
            return avatarUrl;
        }

        public JSONObject getLastMessage() {
            return lastMessage;
        }
    }
}

