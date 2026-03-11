package top.lingyuzhao.codeBookChatApp;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * 在联网时被系统调度执行，用于启动前台服务，便于在联网时唤醒应用以接收新消息。
 */
public class WakeWhenOnlineWorker extends Worker {

    public WakeWhenOnlineWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            if (KeepAliveForegroundService.isDestroyed()) {
                Intent intent = new Intent(getApplicationContext(), KeepAliveForegroundService.class);
                getApplicationContext().startForegroundService(intent);
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
