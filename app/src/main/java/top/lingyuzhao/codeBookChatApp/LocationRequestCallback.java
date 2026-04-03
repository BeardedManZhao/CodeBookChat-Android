package top.lingyuzhao.codeBookChatApp;

/**
 * Service 请求 Activity 执行定位时使用的回调接口。
 * <p>
 * Activity 实现此接口，Service 通过 {@link KeepAliveForegroundService#setLocationRequestCallback}
 * 注册回调；定位完成后，Activity 再通过 Binder 拿到 Service 实例，
 * 调用 {@link KeepAliveForegroundService#onLocationResult} 把数据传回。
 */
public interface LocationRequestCallback {
    /**
     * Service 需要获取位置时回调此方法，Activity 在此方法中启动定位
     *
     * @param once      是否只定位一次
     * @param isAutoReq 是否在没有权限的时候启动请求权限
     */
    void onLocationRequested(boolean once, boolean isAutoReq);

    /**
     * 需要关闭定位器的时候调用此方法
     */
    void onLocationClose();
}