package top.lingyuzhao.codeBookChatApp;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.webkit.DownloadListener;
import android.widget.Toast;

public class WebViewDownloadListener implements DownloadListener {

    private final MainActivity mainActivity;

    // ✅ 不需要传 uri，下载 url 由回调提供
    public WebViewDownloadListener(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
    }

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                String mimetype, long contentLength) {
        new AlertDialog.Builder(mainActivity)
                .setTitle("确认下载")
                .setMessage("是否使用系统浏览器下载该文件？")
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        // ✅ 用回调传入的 url，而不是构造时固定的 uri
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        mainActivity.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(mainActivity, "无法打开下载链接", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}