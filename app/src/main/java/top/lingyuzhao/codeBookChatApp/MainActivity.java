package top.lingyuzhao.codeBookChatApp;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.*;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.*;
import top.lingyuzhao.codeBookChatApp.push.PushNotifyTool;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ValueCallback<Uri[]> mFilePathCallback;
    private WebView webView;
    private ActivityResultLauncher<String> postNotificationsPermissionLauncher;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        postNotificationsPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> { /* ignore */ }
        );

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (mFilePathCallback != null) {
                        Uri[] results = null;
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            String dataString = result.getData().getDataString();
                            if (dataString != null) {
                                results = new Uri[]{Uri.parse(dataString)};
                            }
                        }
                        mFilePathCallback.onReceiveValue(results);
                        mFilePathCallback = null;
                    }
                });

        webView = findViewById(R.id.webview);
        // --- 新的返回键处理逻辑 ---
        OnBackPressedCallback callback = new OnBackPressedCallback(true /* enabled by default */) {
            @Override
            public void handleOnBackPressed() {
                // 在这里实现您原有的逻辑
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    long secondTime = System.currentTimeMillis();
                    if (secondTime - firstBackTime > 2000) {
                        Toast.makeText(MainActivity.this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
                        firstBackTime = secondTime;
                    } else {
                        // 如果间隔小于等于2秒，则允许系统处理返回操作（即退出Activity）
                        // 注意：这里不再是调用 super.onBackPressed()
                        // 我们需要告诉 dispatcher 我们不再拦截这个事件
                        // 通过setEnabled(false) 然后调用默认行为
                        // 最标准的做法是移除此callback或让它失效

                        // 标准做法：设置回调无效，让系统处理默认返回
                        this.setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed(); // 触发系统默认行为
                        // this.setEnabled(true); // 如果需要的话，可以在此处重新启用
                    }
                }
            }
        };

        // 将回调添加到调度器
        getOnBackPressedDispatcher().addCallback(this, callback);
        // 初始化其它东西
        setupNotificationPermissionIfNeeded();
        scheduleWakeWhenOnlineWork();
        setupWebBridge(webView);
        handleNotificationOpenIntent(getIntent());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                final String string = uri.toString();
                Log.i("MainActivity", string);
                if (string.contains("/download") || string.contains("/files")) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("确认跳转")
                            .setMessage("我们会使用您的系统浏览器进行下载，是否继续？")
                            .setPositiveButton("确定", (dialog, which) -> {
                                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                                startActivity(intent);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    return true;
                }
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    fileChooserLauncher.launch(intent);
                } catch (ActivityNotFoundException e) {
                    mFilePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        setupKeyboardInsetListener();
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.loadUrl(AppConstants.CHAT_PAGE_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationOpenIntent(intent);
    }

    private void setupNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            return;
        postNotificationsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
    }

    /**
     * 调度“联网时唤醒”的周期任务，便于在联网时启动前台服务以接收新消息
     */
    private void scheduleWakeWhenOnlineWork() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(WakeWhenOnlineWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "wake_when_online",
                ExistingPeriodicWorkPolicy.KEEP,
                work);
    }

    private void setupWebBridge(WebView webView) {
        // 网页收到 WS 消息后调用：window.CodeBookApp.notify(JSON.stringify(msg))
        webView.addJavascriptInterface(new WebAppBridge(getApplicationContext()), "CodeBookApp");
    }

    private void handleNotificationOpenIntent(Intent intent) {
        if (intent == null || webView == null) return;

        String openUrl = intent.getStringExtra(PushNotifyTool.EXTRA_OPEN_URL);
        if (openUrl != null && !openUrl.isEmpty()) {
            webView.loadUrl(openUrl);
        }

        String rawJson = intent.getStringExtra(PushNotifyTool.EXTRA_MESSAGE_JSON);
        if (rawJson != null && !rawJson.isEmpty()) {
            // 把通知点开的消息回传给网页（可选接收）
            // 网页可以监听：window.addEventListener('nativePush', (e) => console.log(e.detail))
            String escaped = rawJson
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "");
            String js = "(function(){try{window.dispatchEvent(new CustomEvent('nativePush',{detail:JSON.parse('"
                    + escaped + "')}));}catch(e){}})();";
            webView.post(() -> webView.evaluateJavascript(js, null));
        }
    }

    /**
     * 使用 WindowInsets 监听键盘（IME）高度变化。
     * <p>
     * 优点：
     * - 获取的是“真实键盘高度”
     * - 不包含导航栏
     * - 不会产生底部白条
     * - Android 11+ 官方推荐方式
     */
    private void setupKeyboardInsetListener() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content),
                (view, insets) -> {

                    androidx.core.graphics.Insets imeInsets =
                            insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());

                    boolean isKeyboardVisible =
                            insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());

                    if (isKeyboardVisible) {
                        applyKeyboardInset(imeInsets.bottom);
                    } else {
                        applyKeyboardInset(0);
                    }

                    return insets;
                });
    }

    /**
     * 根据键盘高度调整 WebView 底部间距。
     */
    private void applyKeyboardInset(int keyboardHeight) {
        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams params =
                (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) webView.getLayoutParams();

        if (params.bottomMargin != keyboardHeight) {
            params.bottomMargin = keyboardHeight;
            webView.setLayoutParams(params);
        }
    }

    private long firstBackTime = 0;

    public static final class WebAppBridge {
        private final android.content.Context appContext;

        WebAppBridge(android.content.Context appContext) {
            this.appContext = appContext.getApplicationContext();
        }

        @JavascriptInterface
        public void notify(String messageJson) {
            Log.d("WebViewJSInterface", "收到消息JSON: " + messageJson); // 添加日志
            PushNotifyTool.ParsedMessage parsed = PushNotifyTool.ParsedMessage.fromJsonSafe(messageJson);
            if (parsed == null) return;
            // 看看 command 是几
            if (parsed.getCommand() == 5) {// 代表是回传 token，在这里把 token 交给前台服务
                String token = parsed.getBody();
                if (token != null && !token.isEmpty()) {
                    // 开始连接
                    Intent intent = new Intent(appContext, KeepAliveForegroundService.class);
                    intent.putExtra(KeepAliveForegroundService.EXTRA_WS_TOKEN, token);
                    intent.putExtra(KeepAliveForegroundService.EXTRA_NOTIFICATION_TEXT, "连接成功！");
                    Log.d("WebViewJSInterface", "userId=" + parsed.getRecId());
                    intent.putExtra("userId", parsed.getRecId());
                    appContext.startForegroundService(intent);
                }
            } else {
                // 代表是其它的 直接走这个
                PushNotifyTool.notifyFromWebSocketJson(appContext, parsed);
            }
        }
    }
}