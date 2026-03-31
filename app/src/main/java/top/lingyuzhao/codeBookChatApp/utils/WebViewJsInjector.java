package top.lingyuzhao.codeBookChatApp.utils;

import android.webkit.ValueCallback;
import android.webkit.WebView;


public class WebViewJsInjector {

    public final static ValueCallback<String> NO_HANDLER = s -> {
        
    };

    /**
     * 向 WebView 注入 JS 代码（主线程执行）
     * @param webView WebView 实例
     * @param handler 用于处理函数返回值的回调
     * @param jsCode JS 代码（不要带 javascript: 前缀）
     */
    public static void inject(WebView webView, String jsCode, ValueCallback<String> handler)  {
        if (webView == null || jsCode == null) return;

        webView.post(() -> webView.evaluateJavascript(jsCode, handler));
    }

    /**
     * 向 WebView 注入 JS 代码（主线程执行）但是不接受返回值
     * @param webView WebView 实例
     * @param jsCode JS 代码（不要带 javascript: 前缀）
     */
    public static void inject(WebView webView, String jsCode)  {
        inject(webView, jsCode, NO_HANDLER);
    }

}