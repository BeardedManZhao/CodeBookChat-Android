package top.lingyuzhao.codeBookChatApp.utils;

import java.util.Calendar;
import java.util.TimeZone;

public class FastTimeFormatter {

    // 使用 UTC 时区的日历实例，避免时区转换带来的开销
    private static final Calendar CALENDAR = Calendar.getInstance(TimeZone.getDefault());

    /**
     * 将毫秒时间戳转换为当天的 HH:mm:ss 格式。
     * 例如: 1704067261000L (代表某天的 00:01:01) -> "00:01:01"
     *
     * @param timestamp 毫秒时间戳
     * @return 格式化后的字符串
     */
    public static String formatTimestampToHHMMSS(long timestamp) {
        // 设置时间戳
        CALENDAR.setTimeInMillis(timestamp);

        // 获取当天的小时、分钟、秒
        int hour = CALENDAR.get(Calendar.HOUR_OF_DAY);
        int minute = CALENDAR.get(Calendar.MINUTE);
        int second = CALENDAR.get(Calendar.SECOND);

        // 使用 StringBuilder 构建结果字符串
        StringBuilder sb = new StringBuilder(8);

        // 格式化小时
        if (hour < 10) {
            sb.append('0');
        }
        sb.append(hour).append(':');

        // 格式化分钟
        if (minute < 10) {
            sb.append('0');
        }
        sb.append(minute).append(':');

        // 格式化秒
        if (second < 10) {
            sb.append('0');
        }
        sb.append(second);

        return sb.toString();
    }

    // --- 测试代码 ---
    public static void main(String[] args) {
        // 示例时间戳 (代表 2024-01-01 00:01:01 UTC)
        long exampleTimestamp = 1704067261000L;
        System.out.println(formatTimestampToHHMMSS(exampleTimestamp)); // 输出: 00:01:01

        // 当前时间的时间戳
        long currentTimeMillis = System.currentTimeMillis();
        System.out.println(formatTimestampToHHMMSS(currentTimeMillis));
    }
}