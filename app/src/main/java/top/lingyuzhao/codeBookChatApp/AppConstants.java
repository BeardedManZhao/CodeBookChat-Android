package top.lingyuzhao.codeBookChatApp;

/**
 * 应用内使用的根网址等常量，修改此处即可全局生效。
 */
public final class AppConstants {

    /**
     * 根域名（无协议），例如：chat.lingyuzhao.top
     */
    public static final String HOST = "192.168.0.9:8080";
    /**
     * 根网址（HTTPS），例如：https://chat.lingyuzhao.top
     */
    public static final String BASE_URL = "http://" + HOST;
    /**
     * 聊天页路径：{@link #BASE_URL}/chat.html
     */
    public static final String CHAT_PAGE_URL = BASE_URL + "/chat.html";
    public static final String LOGO_URL = BASE_URL + "/image/logo.jpg";
    /**
     * WebSocket 根地址（WSS），例如：wss://chat.lingyuzhao.top
     */
    public static final String WSS_BASE = "ws://" + HOST;
    public static final String PACKAGE_NAME = "top.lingyuzhao.codeBookChatApp";

    private AppConstants() {
    }
}
