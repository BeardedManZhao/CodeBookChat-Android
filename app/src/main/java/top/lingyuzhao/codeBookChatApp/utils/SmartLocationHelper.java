package top.lingyuzhao.codeBookChatApp.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 一个轻量的智能定位工具。
 *
 * <p>特性：</p>
 * <ul>
 *     <li>支持定位权限检查与 GPS 开关检查</li>
 *     <li>支持一次性定位与持续定位</li>
 *     <li>持续定位时按固定周期推送最新位置，而不是每次系统回调都直接通知</li>
 *     <li>支持静止检测：当前位置与上次缓存位置差异过小时，不更新缓存</li>
 *     <li>支持防重复启动</li>
 * </ul>
 *
 * <p>推荐用法：</p>
 * <pre>{@code
 * SmartLocationHelper loc = new SmartLocationHelper(this);
 * loc.setEnabled(true);
 * loc.setUpdateConfig(2000, 0); // 减少系统回调压力
 *
 * if (!loc.check(this)) return;
 *
 * loc.start(false, new SmartLocationHelper.OnLocationCallback() {
 *     @Override
 *     public void onLocation(double lat, double lng, Location raw) {
 *         // 每 5 秒收到一次最新位置
 *     }
 *
 *     @Override
 *     public void onError(boolean once, String msg) {
 *         // 错误处理
 *     }
 * });
 * }</pre>
 */
@SuppressWarnings("unused")
public class SmartLocationHelper {

    private static final int REQ_PERMISSION = 10001;

    /** 持续模式下的默认推送间隔：5 秒 */
    private static final long DEFAULT_PUSH_INTERVAL_MS = 5000L;

    /**
     * 默认静止过滤阈值（米）。
     * 当新位置与上次缓存位置的距离小于该值时，认为位置基本没变，不刷新缓存。
     */
    private static final float DEFAULT_STATIONARY_THRESHOLD_METERS = 5f;

    private final Context context;
    private final LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();

    /** 是否允许工作 */
    private volatile boolean enabled = false;

    /** 当前是否正在定位 */
    private volatile boolean running = false;

    /** 是否正处于启动流程中 */
    private volatile boolean starting = false;

    /** 是否一次性定位 */
    private volatile boolean once = false;

    /**
     * 系统定位更新最小时间间隔。
     * 建议持续模式下设置为 2000 左右即可，避免系统回调过于频繁。
     */
    private long minTime = 1000L;

    /**
     * 系统定位更新最小距离。
     * 建议持续模式下可以设置为 0，让内部自己做静止过滤。
     */
    private float minDistance = 0f;

    /** 单次定位超时时间，仅用于 once = true 的模式 */
    private long timeoutMs = 10000L;

    /** 持续模式下主动推送的间隔 */
    private long pushIntervalMs = DEFAULT_PUSH_INTERVAL_MS;

    /** 静止过滤阈值，单位：米 */
    private float stationaryThresholdMeters = DEFAULT_STATIONARY_THRESHOLD_METERS;

    /** 当前缓存的位置 */
    private volatile Location lastLocation;

    /** 当前会话的 listener */
    private LocationListener currentListener;

    /** 当前会话的推送任务 */
    private Runnable currentPushTask;

    /** 当前会话的超时任务 */
    private Runnable currentTimeoutTask;

    /** 用于让旧任务失效的 token */
    private int startToken = 0;

    /**
     * 构造定位工具。
     *
     * @param ctx 上下文，内部会自动转为 ApplicationContext，避免泄漏 Activity
     */
    public SmartLocationHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.locationManager = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * 检查定位权限与 GPS 开关状态。
     *
     * <p>当没有权限时，会自动请求权限；当 GPS 未开启时，会跳转到系统定位设置页。</p>
     *
     * @param activity 当前 Activity
     * @return true 表示权限和 GPS 状态都满足，false 表示还需要用户处理
     */
    public boolean check(Activity activity) {
        if (notHasPermission()) {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_PERMISSION
            );
            return false;
        }

        if (!isGpsOpen()) {
            activity.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return false;
        }

        return true;
    }

    /**
     * 处理权限申请结果。
     *
     * @param requestCode 请求码
     * @param grantResults 权限结果
     * @return true 表示权限已授予，false 表示未授予
     */
    public boolean onPermissionResult(int requestCode, int[] grantResults) {
        return requestCode == REQ_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 启用或禁用定位功能。
     *
     * <p>禁用后会立即停止当前定位会话。</p>
     *
     * @param enable true 表示启用，false 表示禁用
     */
    public void setEnabled(boolean enable) {
        this.enabled = enable;
        if (!enable) {
            stop();
        }
    }

    /**
     * 设置单次定位超时时间。
     *
     * <p>仅在 {@code once = true} 时生效。</p>
     *
     * @param ms 超时时间，单位毫秒
     */
    public void setTimeout(long ms) {
        this.timeoutMs = ms;
    }

    /**
     * 设置系统定位更新参数。
     *
     * <p>这是给 {@link LocationManager#requestLocationUpdates(String, long, float, LocationListener, Looper)}
     * 用的参数，建议持续定位模式下用较小的时间间隔、较小的距离阈值，
     * 让系统尽量少做无意义的回调，然后由本类内部按 5 秒节流推送。</p>
     *
     * <p>例如：</p>
     * <pre>{@code
     * loc.setUpdateConfig(2000, 0);
     * }</pre>
     *
     * @param minTime 最小更新时间，单位毫秒
     * @param minDistance 最小更新距离，单位米
     */
    public void setUpdateConfig(long minTime, float minDistance) {
        this.minTime = minTime;
        this.minDistance = minDistance;
    }

    /**
     * 设置持续模式下的主动推送间隔。
     *
     * <p>默认是 5 秒。持续模式下，系统定位可能更频繁，
     * 但真正回调给外部时会按这个时间间隔统一推送。</p>
     *
     * @param ms 推送间隔，单位毫秒
     */
    public void setPushInterval(long ms) {
        if (ms > 0) {
            this.pushIntervalMs = ms;
        }
    }

    /**
     * 设置静止检测阈值。
     *
     * <p>当新位置与上次缓存位置的距离小于该阈值时，认为用户基本没有移动，
     * 这时不会刷新缓存位置。</p>
     *
     * <p>建议值：</p>
     * <ul>
     *     <li>3~5 米：比较灵敏</li>
     *     <li>10 米左右：更稳，适合省电</li>
     * </ul>
     *
     * @param meters 阈值，单位米
     */
    public void setStationaryThreshold(float meters) {
        if (meters >= 0) {
            this.stationaryThresholdMeters = meters;
        }
    }

    /**
     * 启动定位。
     *
     * <p>规则：</p>
     * <ul>
     *     <li>如果当前正在运行或正在启动，直接忽略这次调用</li>
     *     <li>once = true：拿到一个位置就直接回调并停止</li>
     *     <li>once = false：只缓存位置，由内部每隔固定时间推送一次最新缓存</li>
     * </ul>
     *
     * @param once true 表示单次定位；false 表示持续定位
     * @param cb 定位回调
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    public void start(final boolean once, final OnLocationCallback cb) {
        if (cb == null) return;
        if (!enabled) return;

        final int myToken;
        synchronized (stateLock) {
            if (running || starting) {
                return; // 防重复启动
            }
            starting = true;
            this.once = once;
            myToken = ++startToken;
        }

        if (notHasPermission()) {
            failStartIfNeeded(myToken, once, cb, "没权限");
            return;
        }

        final String provider = chooseProvider();
        if (provider == null) {
            failStartIfNeeded(myToken, once, cb, "没有可用定位方式");
            return;
        }

        final LocationListener sessionListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                if (isNotTokenActive(myToken)) return;

                if (once) {
                    dispatch(cb, location);
                    stop();
                    return;
                }

                // 持续模式：只缓存，不直接回调
                // 静止时不更新缓存，减少无意义波动
                updateCacheIfNeeded(location);
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                if (isNotTokenActive(myToken)) return;
                stop();
                error(cb, once, "定位被关闭");
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
                // no-op
            }

            @Override
            public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
                // 旧 API 回调，保留空实现以兼容低版本写法
            }
        };

        final Runnable sessionTimeoutTask = () -> {
            if (isNotTokenActive(myToken)) return;

            stop();
            error(cb, once, "定位超时");
        };

        final Runnable sessionPushTask = new Runnable() {
            @Override
            public void run() {
                if (isNotTokenActive(myToken)) return;

                Location latest = lastLocation;
                if (latest != null) {
                    dispatch(cb, latest);
                }

                handler.postDelayed(this, pushIntervalMs);
            }
        };

        synchronized (stateLock) {
            if (myToken != startToken || !starting) {
                return;
            }
            currentListener = sessionListener;
            currentTimeoutTask = sessionTimeoutTask;
            currentPushTask = sessionPushTask;

            running = true;
            starting = false;
        }

        // 先尝试缓存位置
        try {
            Location last = locationManager.getLastKnownLocation(provider);
            if (last != null) {
                if (once) {
                    dispatch(cb, last);
                    stop();
                    return;
                } else {
                    lastLocation = last;
                }
            }
        } catch (Exception ignored) {
        }

        // 注册监听
        try {
            locationManager.requestLocationUpdates(
                    provider,
                    minTime,
                    minDistance,
                    sessionListener,
                    Looper.getMainLooper()
            );
        } catch (Exception e) {
            stop();
            error(cb, once, "启动失败：" + e.getMessage());
            return;
        }

        if (once) {
            handler.postDelayed(sessionTimeoutTask, timeoutMs);
        } else {
            handler.postDelayed(sessionPushTask, pushIntervalMs);
        }
    }

    /**
     * 停止定位。
     *
     * <p>会移除系统监听、取消超时任务、取消持续推送任务，
     * 并让本次会话之后的旧回调全部失效。</p>
     */
    public void stop() {
        LocationListener listenerToRemove;
        Runnable timeoutToRemove;
        Runnable pushToRemove;

        synchronized (stateLock) {
            if (!running && !starting) return;

            startToken++; // 让旧回调/旧任务立即失效
            running = false;
            starting = false;

            listenerToRemove = currentListener;
            timeoutToRemove = currentTimeoutTask;
            pushToRemove = currentPushTask;

            currentListener = null;
            currentTimeoutTask = null;
            currentPushTask = null;
        }

        try {
            if (listenerToRemove != null) {
                locationManager.removeUpdates(listenerToRemove);
            }
        } catch (Exception ignored) {
        }

        if (timeoutToRemove != null) {
            handler.removeCallbacks(timeoutToRemove);
        }

        if (pushToRemove != null) {
            handler.removeCallbacks(pushToRemove);
        }
    }

    /**
     * 判断当前是否正在定位。
     *
     * @return true 表示正在运行
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 选择可用的定位 provider。
     *
     * <p>优先网络定位，再尝试 GPS 定位。</p>
     *
     * @return provider 名称，若都不可用则返回 null
     */
    private String chooseProvider() {
        boolean gps = isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean net = isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (net) return LocationManager.NETWORK_PROVIDER;
        if (gps) return LocationManager.GPS_PROVIDER;

        return null;
    }

    /**
     * 如果新位置与上次缓存位置差异足够大，则更新缓存。
     *
     * <p>这个方法用于“静止检测不更新”。</p>
     *
     * @param newLocation 新位置
     */
    private void updateCacheIfNeeded(Location newLocation) {
        Location old = lastLocation;
        if (old == null) {
            lastLocation = newLocation;
            return;
        }

        float distance = old.distanceTo(newLocation);
        if (distance >= stationaryThresholdMeters) {
            lastLocation = newLocation;
        }
    }

    /**
     * 立即回调位置。
     *
     * @param cb 回调
     * @param loc 定位对象
     */
    private void dispatch(OnLocationCallback cb, Location loc) {
        if (cb != null && loc != null) {
            cb.onLocation(loc.getLatitude(), loc.getLongitude(), loc);
        }
    }

    /**
     * 回调错误。
     *
     * @param cb 回调
     * @param once 是否单次模式
     * @param msg 错误信息
     */
    private void error(OnLocationCallback cb, boolean once, String msg) {
        if (cb != null) {
            cb.onError(once, msg);
        }
    }

    /**
     * 启动失败时统一处理。
     *
     * @param token 本次会话 token
     * @param once 是否单次模式
     * @param cb 回调
     * @param msg 错误信息
     */
    private void failStartIfNeeded(int token, boolean once, OnLocationCallback cb, String msg) {
        boolean shouldNotify = false;

        synchronized (stateLock) {
            if (token == startToken) {
                shouldNotify = true;
                startToken++;
            }

            running = false;
            starting = false;

            if (currentListener != null) {
                try {
                    locationManager.removeUpdates(currentListener);
                } catch (Exception ignored) {
                }
            }

            if (currentTimeoutTask != null) {
                handler.removeCallbacks(currentTimeoutTask);
            }

            if (currentPushTask != null) {
                handler.removeCallbacks(currentPushTask);
            }

            currentListener = null;
            currentTimeoutTask = null;
            currentPushTask = null;
        }

        if (shouldNotify) {
            error(cb, once, msg);
        }
    }

    /**
     * 判断当前 token 是否仍然有效。
     *
     * @param token 会话 token
     * @return true 表示当前回调仍属于有效会话
     */
    private boolean isNotTokenActive(int token) {
        synchronized (stateLock) {
            return token != startToken || !running;
        }
    }

    /**
     * 检查定位权限是否缺失。
     *
     * @return true 表示没有权限
     */
    private boolean notHasPermission() {
        return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 判断 GPS 是否开启。
     *
     * @return true 表示 GPS 已开启
     */
    private boolean isGpsOpen() {
        return isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    /**
     * 判断指定 provider 是否可用。
     *
     * @param p provider 名称
     * @return true 表示可用
     */
    private boolean isProviderEnabled(String p) {
        try {
            return locationManager.isProviderEnabled(p);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 定位回调接口。
     */
    public interface OnLocationCallback {

        /**
         * 定位成功。
         *
         * @param lat 纬度
         * @param lng 经度
         * @param raw 原始 Location 对象
         */
        void onLocation(double lat, double lng, Location raw);

        /**
         * 定位失败。
         *
         * @param once 是否是单次定位
         * @param msg 错误信息
         */
        void onError(boolean once, String msg);
    }
}