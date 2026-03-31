package top.lingyuzhao.codeBookChatApp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import androidx.core.app.ActivityCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import top.lingyuzhao.codeBookChatApp.push.PushNotifyTool;
import top.lingyuzhao.codeBookChatApp.utils.SmartLocationHelper;
import top.lingyuzhao.codeBookChatApp.utils.WebViewJsInjector;

public class MainActivity extends AppCompatActivity implements LocationRequestCallback {

    private static final String TAG = "MainActivity";

    // ------------------------------------------------------------------ //
    //  与 KeepAliveForegroundService 的绑定
    // ------------------------------------------------------------------ //

    /**
     * 绑定成功后持有的 Service 实例（null 表示未绑定）
     */
    private KeepAliveForegroundService boundService = null;
    private boolean isBound = false;

    /**
     * ServiceConnection：Activity 绑定 / 解绑时由系统回调。
     * 绑定成功后立即向 Service 注册当前 Activity 作为定位回调接收方。
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            KeepAliveForegroundService.LocalBinder localBinder =
                    (KeepAliveForegroundService.LocalBinder) service;
            boundService = localBinder.getService();
            boundService.setLocationRequestCallback(MainActivity.this);
            isBound = true;
            Log.i(TAG, "已绑定到 KeepAliveForegroundService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
            isBound = false;
            Log.w(TAG, "KeepAliveForegroundService 连接断开");
        }
    };

    // ------------------------------------------------------------------ //
    //  其它字段
    // ------------------------------------------------------------------ //

    private SmartLocationHelper loc;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ValueCallback<Uri[]> mFilePathCallback;
    private WebView webView;
    private ActivityResultLauncher<String> postNotificationsPermissionLauncher;
    private FrameLayout fullscreenContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private long firstBackTime = 0;
    private String pendingUid;

    // ------------------------------------------------------------------ //
    //  生命周期
    // ------------------------------------------------------------------ //

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (loc != null) {
            loc.stop();
        }
        loc = new SmartLocationHelper(this);
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
                            if (dataString != null) results = new Uri[]{Uri.parse(dataString)};
                        }
                        mFilePathCallback.onReceiveValue(results);
                        mFilePathCallback = null;
                    }
                });

        webView = findViewById(R.id.webview);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        fullscreenContainer = new FrameLayout(this);

        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (customViewCallback != null) {
                    exitFullscreen();
                    return;
                }
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    long now = System.currentTimeMillis();
                    if (now - firstBackTime > 2000) {
                        Toast.makeText(MainActivity.this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
                        firstBackTime = now;
                    } else {
                        this.setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);

        setupNotificationPermissionIfNeeded();
        scheduleWakeWhenOnlineWork();
        setupWebBridge(webView);

        webView.setDownloadListener(new WebViewDownloadListener(this));

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (pendingUid != null) {
                    tryOpenChatByJs(pendingUid);
                    pendingUid = null;
                }

                // ✅ 主动检测 JS 环境
                view.evaluateJavascript(
                        "typeof window.CodeBookApp !== 'undefined'",
                        value -> Log.d("WebView", "JS Bridge 是否存在: " + value)
                );
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                final String string = uri.toString();
                Log.i(TAG, string);
                if (string.contains("/download") || string.contains("/files")) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("确认跳转")
                            .setMessage("我们会使用您的系统浏览器进行下载，是否继续？")
                            .setPositiveButton("确定", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, uri)))
                            .setNegativeButton("取消", null).show();
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) mFilePathCallback.onReceiveValue(null);
                mFilePathCallback = filePathCallback;
                try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent());
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
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                decorView.addView(fullscreenContainer, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        setupKeyboardInsetListener();

        final WebSettings settings = webView.getSettings();
        // 已有设置...
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // 新增/优化性能设置
        // settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);         // 优先使用缓存，减少网络等待
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);                               // 允许图片加载（可根据需要改）

        // 硬件加速（强烈推荐，对复杂页面效果明显）
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);               // 或在 XML / Manifest 中全局开启

        // 其他常用加速
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);                               // 如果需要定位

        final Intent intent = getIntent();
        final Uri uri = intent.getData();
        if (handleNotificationOpenIntent(intent) && uri != null) {
            webView.loadUrl(uri.toString());
        } else {
            webView.loadUrl(AppConstants.CHAT_PAGE_URL);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 绑定 Service，Service 已由 WebAppBridge 通过 startForegroundService 启动
        // BIND_AUTO_CREATE 可确保即使 Service 还未启动时也能成功绑定
        Intent bindIntent = new Intent(this, KeepAliveForegroundService.class);
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
            boundService = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationOpenIntent(intent);
        if (pendingUid != null) {
            tryOpenChatByJs(pendingUid);
            pendingUid = null;
        }
    }

    // ------------------------------------------------------------------ //
    //  LocationRequestCallback 实现（核心修复）
    // ------------------------------------------------------------------ //

    /**
     * Service 需要定位时回调此方法。
     * 定位成功后通过 Binder 引用调用 Service.onLocationResult，由 Service 负责上传。
     */
    @Override
    public void onLocationRequested(boolean once) {
        if (loc.isEnabled() && once && !loc.isOnce()) {
            // 代表不需要操作 因为 单次 多次 都是没什么区别的
            Log.i(TAG, "onLocationRequested 检测到不需要启动，因为目前处于实时检测状态，一次性启动可被实时状态包裹其中，直接跳过");
            return;
        }
        if (!loc.check(this)) {
            Log.w(TAG, "onLocationRequested：check 不通过，已跳过");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "onLocationRequested：缺少定位权限，已跳过");
            return;
        }
        loc.setEnabled(true);
        loc.start(once, new SmartLocationHelper.OnLocationCallback() {
            @Override
            public void onLocation(double lat, double lng, Location raw) {
                Log.d(TAG, "定位成功!");
                // ✅ 核心修复：将定位结果回传给 Service
                if (isBound && boundService != null) {
                    boundService.onLocationResult(lat, lng, raw);
                    // 设置到雷达 TODO 用户对象需要
                    WebViewJsInjector.inject(webView, "radar.setSelf(selfUser, " + lng + ", " + lat + ", " + System.currentTimeMillis() + ");");
                } else {
                    Log.w(TAG, "定位成功但 Service 未绑定，无法回传");
                }
            }

            @Override
            public void onError(boolean once, String msg) {
                Log.e(TAG, "定位失败: " + msg + "; " + (once ? "已停止定位" : "尝试重新启动！"));
                if (!once && msg.endsWith("超时")) {
                    onLocationRequested(false); // 如果是持续定位且是超时的错误就重启，不得被断开
                }
            }
        });
    }

    @Override
    public void onLocationClose() {
        loc.setEnabled(false);
    }

    // ------------------------------------------------------------------ //
    //  工具方法
    // ------------------------------------------------------------------ //

    private void tryOpenChatByJs(String uid) {
        String js = "(function(){" +
                "let retry=0;" +
                "function open(){" +
                "  let list=document.getElementsByClassName('friend-item');" +
                "  if(list.length===0){if(retry++<10){setTimeout(open,300);}return;}" +
                "  for(let el of list){" +
                "    if(el.getAttribute('data-uid')=='" + uid + "'){el.click();return;}" +
                "  }" +
                "}" +
                "open();" +
                "})();";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void exitFullscreen() {
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        fullscreenContainer.removeAllViews();
        if (fullscreenContainer.getParent() != null)
            ((ViewGroup) fullscreenContainer.getParent()).removeView(fullscreenContainer);
        webView.setVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void setupNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            return;
        postNotificationsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
    }

    private void scheduleWakeWhenOnlineWork() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                WakeWhenOnlineWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "wake_when_online", ExistingPeriodicWorkPolicy.KEEP, work);
    }

    private void setupWebBridge(WebView webView) {
        // WebAppBridge 已拆分为独立类
        webView.addJavascriptInterface(new WebAppBridge(getApplicationContext()), "CodeBookApp");
    }

    private boolean handleNotificationOpenIntent(Intent intent) {
        if (intent == null) return false;
        Uri uri = intent.getData();
        if (uri != null) pendingUid = uri.getQueryParameter("uid");

        String rawJson = intent.getStringExtra(PushNotifyTool.EXTRA_MESSAGE_JSON);
        if (rawJson != null && !rawJson.isEmpty()) {
            String escaped = rawJson.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "");
            String js = "(function(){try{window.dispatchEvent(new CustomEvent('nativePush'," +
                    "{detail:JSON.parse('" + escaped + "')}));}catch(e){}})();";
            webView.post(() -> webView.evaluateJavascript(js, null));
        }
        return pendingUid != null;
    }

    private void setupKeyboardInsetListener() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content),
                (view, insets) -> {
                    androidx.core.graphics.Insets imeInsets =
                            insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
                    boolean visible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
                    applyKeyboardInset(visible ? imeInsets.bottom : 0);
                    return insets;
                });
    }

    private void applyKeyboardInset(int keyboardHeight) {
        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams params =
                (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) webView.getLayoutParams();
        if (params.bottomMargin != keyboardHeight) {
            params.bottomMargin = keyboardHeight;
            webView.setLayoutParams(params);
        }
    }
}