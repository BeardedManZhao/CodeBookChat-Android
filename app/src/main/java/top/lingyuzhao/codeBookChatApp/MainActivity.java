package top.lingyuzhao.codeBookChatApp;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import top.lingyuzhao.codeBookChatApp.push.PushNotifyTool;

public class MainActivity extends AppCompatActivity {
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ValueCallback<Uri[]> mFilePathCallback;
    private WebView webView;
    private ActivityResultLauncher<String> postNotificationsPermissionLauncher;
    private FrameLayout fullscreenContainer; // 用于承载全屏视频的容器
    private WebChromeClient.CustomViewCallback customViewCallback;
    private long firstBackTime = 0;
    private String pendingUid;

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
        // ⚠️ 必须在 setWebChromeClient 之前初始化，否则回调触发时为 null
        fullscreenContainer = new FrameLayout(this);
        // --- 新的返回键处理逻辑 ---
        OnBackPressedCallback callback = new OnBackPressedCallback(true /* enabled by default */) {
            @Override
            public void handleOnBackPressed() {
                // 如果现在是全屏就先退出全屏
                if (customViewCallback != null) {
                    exitFullscreen();
                    return;
                }
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

        // ✅ 修复3：补充 DownloadListener，捕获 Content-Disposition 触发的下载（视频等）
        webView.setDownloadListener(new WebViewDownloadListener(this));
        // 设置 web 客户端 并实现其中的处理方案
        webView.setWebViewClient(new WebViewClient() {

            // ✅ 新增这个
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // 页面加载完成后再执行 JS
                if (pendingUid != null) {
                    tryOpenChatByJs(pendingUid);
                    pendingUid = null;
                }
            }

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

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customViewCallback != null) {
                    onHideCustomView();
                    return;
                }

                customViewCallback = callback;
                webView.setVisibility(View.GONE);

                ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
                fullscreenContainer.addView(view, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                decorView.addView(fullscreenContainer, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

                // ✅ 跟随传感器，横竖由用户决定
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }

            @Override
            public void onHideCustomView() {
                // ✅ 修复1：只调用 exitFullscreen()，不再重复移除，避免 NPE
                exitFullscreen();
            }
        });
        setupKeyboardInsetListener();
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        // 检查看看有没有携带 openUrl
        final Intent intent = getIntent();
        final Uri uri = intent.getData();
        if (handleNotificationOpenIntent(intent) && uri != null) {
            webView.loadUrl(uri.toString());
        } else {
            webView.loadUrl(AppConstants.CHAT_PAGE_URL);
        }
    }

    /**
     * 尝试打开一个好友的聊天页面
     *
     * @param uid 好友的id
     */
    private void tryOpenChatByJs(String uid) {
        String js = "(function(){" +
                "let retry=0;" +
                "function open(){" +
                "  let list=document.getElementsByClassName('friend-item');" +
                "  if(list.length===0){" +
                "    if(retry++<10){setTimeout(open,300);}return;" +
                "  }" +
                "  for(let el of list){" +
                "    if(el.getAttribute('data-uid')=='" + uid + "')" +
                "    {el.click();return;}" +
                "  }" +
                "}" +
                "open();" +
                "})();";

        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    /**
     * 封装退出全屏的逻辑（唯一出口，避免重复移除导致 NPE）
     */
    private void exitFullscreen() {
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }

        fullscreenContainer.removeAllViews();
        if (fullscreenContainer.getParent() != null) {
            ((ViewGroup) fullscreenContainer.getParent()).removeView(fullscreenContainer);
        }

        webView.setVisibility(View.VISIBLE);

        // ✅ 退出后恢复跟随传感器（或改为 UNSPECIFIED 恢复系统默认）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        handleNotificationOpenIntent(intent);

        // ✅ 如果页面已经加载过，直接执行 JS（不 reload！）
        if (pendingUid != null) {
            tryOpenChatByJs(pendingUid);
            pendingUid = null;
        }
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

    private boolean handleNotificationOpenIntent(Intent intent) {
        if (intent == null) return false;

        Uri uri = intent.getData();
        if (uri != null) {
            pendingUid = uri.getQueryParameter("uid");
        }

        // JSON 那段保持不动
        String rawJson = intent.getStringExtra(PushNotifyTool.EXTRA_MESSAGE_JSON);
        if (rawJson != null && !rawJson.isEmpty()) {
            String escaped = rawJson
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "");
            String js = "(function(){try{window.dispatchEvent(new CustomEvent('nativePush',{detail:JSON.parse('"
                    + escaped + "')}));}catch(e){}})();";
            webView.post(() -> webView.evaluateJavascript(js, null));
        }

        return pendingUid != null;
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