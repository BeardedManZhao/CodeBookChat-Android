package top.lingyuzhao.codeBookChatApp.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import top.lingyuzhao.codeBookChatApp.R;

public class AvatarLoader {

    private static final Map<String, Bitmap> memoryCache = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void load(Context context, String url, Callback callback) {
        if (url == null || url.isEmpty()) {
            callback.onLoaded(getDefault(context));
            return;
        }

        // 1️⃣ 内存缓存
        Bitmap cached = memoryCache.get(url);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }

        // 2️⃣ 本地缓存
        File file = new File(context.getCacheDir(), md5(url));
        if (file.exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
            memoryCache.put(url, bmp);
            callback.onLoaded(bmp);
            return;
        }

        // 3️⃣ 没有 → 先返回默认
        callback.onLoaded(getDefault(context));

        // 4️⃣ 后台下载
        executor.execute(() -> {
            try {
                URL u = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                InputStream is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();

                if (bmp != null) {
                    memoryCache.put(url, bmp);

                    // 写入本地
                    FileOutputStream fos = new FileOutputStream(file);
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();

                    // 回调主线程
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onLoaded(bmp);
                    });
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static Bitmap getDefault(Context context) {
        return BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher);
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }
}