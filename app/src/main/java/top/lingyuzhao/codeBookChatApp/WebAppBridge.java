package top.lingyuzhao.codeBookChatApp;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.webkit.JavascriptInterface;

import top.lingyuzhao.codeBookChatApp.push.PushNotifyTool;

/**
 * 注入到 WebView 的 JS Bridge，网页通过 window.CodeBookApp.notify(json) 调用。
 * <p>
 * 使用方式（在 Activity 中）：
 * <pre>
 *   webView.addJavascriptInterface(new WebAppBridge(this), "CodeBookApp");
 * </pre>
 */
public class WebAppBridge {

    private static final String TAG = "WebViewJSInterface";

    private final Context appContext;

    public WebAppBridge(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * 网页调用入口，messageJson 为服务端下发的消息体。
     *
     * <ul>
     *   <li>command == 5：token 回传，启动前台 Service 建立 WebSocket 连接</li>
     *   <li>其它 command：转交 {@link PushNotifyTool} 显示推送通知</li>
     * </ul>
     */
    @JavascriptInterface
    public void notify(String messageJson) {
        Log.d(TAG, "收到消息JSON: " + messageJson);

        PushNotifyTool.ParsedMessage parsed = PushNotifyTool.ParsedMessage.fromJsonSafe(messageJson);
        if (parsed == null) return;

        if (parsed.getCommand() == 5) {
            // command=5：网页回传 token，启动/更新前台 Service
            String token = parsed.getBody();
            if (token != null && !token.isEmpty()) {
                Log.d(TAG, "收到 token，启动前台 Service，userId=" + parsed.getRecId());
                Intent intent = new Intent(appContext, KeepAliveForegroundService.class);
                intent.putExtra(KeepAliveForegroundService.EXTRA_WS_TOKEN, token);
                intent.putExtra(KeepAliveForegroundService.EXTRA_NOTIFICATION_TEXT, "连接成功！");
                intent.putExtra("userId", parsed.getRecId());
                intent.putExtra("sessionId", parsed.getSessionId());
                appContext.startForegroundService(intent);
            }
        } else if (parsed.getCommand() == 12) {
            // 代表回传的webSocketSessionId
            Intent intent = new Intent(appContext, KeepAliveForegroundService.class);
            // 提取到里面的 sessionId
            final String sessionId = parsed.getSessionId();
            // 发给服务
            intent.putExtra("sessionId", sessionId);
            appContext.startForegroundService(intent);
        } else {
            // 其它消息：显示推送通知
            PushNotifyTool.notifyFromWebSocketJson(appContext, parsed);
        }
    }
}