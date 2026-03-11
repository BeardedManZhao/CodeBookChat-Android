package top.lingyuzhao.codeBookChatApp;

/**
 * 应用内使用的根网址等常量，修改此处即可全局生效。
 */
public final class AppConstants {

    private AppConstants() {
    }

    /** 根域名（无协议），例如：chat.lingyuzhao.top */
    public static final String HOST = "chat.lingyuzhao.top";

    /** 根网址（HTTPS），例如：https://chat.lingyuzhao.top */
    public static final String BASE_URL = "https://" + HOST;

    /** WebSocket 根地址（WSS），例如：wss://chat.lingyuzhao.top */
    public static final String WSS_BASE = "wss://" + HOST;

    /** 聊天页路径：{@link #BASE_URL}/chat.html */
    public static final String CHAT_PAGE_URL = BASE_URL + "/chat.html";

    public static final String PACKAGE_NAME = "top.lingyuzhao.codeBookChatApp";
}
