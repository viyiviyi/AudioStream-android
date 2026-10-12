package com.yiyiooo.audiostream;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * 全应用共用的一条日志。
 *
 * 为什么不放在某个界面上：日志页只是「看」它的那一方，而写它的人分布在各处——播放页的连接动作、
 * 共享页的开关、设置页的改动。放在 Fragment 或 Activity 里，切页就丢、重建就空，
 * 用户切回去看到一片空白，正好在他最需要线索的时候。
 *
 * 只保留最近若干行：连接一直重试能产出上万行，全留着只会把内存一点点吃光，
 * 而用户实际要看的永远是最后那几十行。
 *
 * {@link #add} 可能从后台线程调用（服务里的事件），所以内部自己同步，并统一回到主线程通知界面。
 */
public final class AppLog {

    private static final String TAG = "AudioStream";

    /** 留多少行。400 行在手机上已经要翻很久，再多的部分用户不会看。 */
    private static final int MAX_LINES = 400;

    private static final AppLog INSTANCE = new AppLog();

    public static AppLog get() {
        return INSTANCE;
    }

    /** 日志变了。回调在主线程。 */
    public interface Listener {
        void onLogChanged();
    }

    private final Deque<String> lines = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Listener listener;

    private AppLog() {
    }

    /** 记一条。同时进 logcat——真出问题时把手机连上电脑看日志比对着屏幕截图有用得多。 */
    public void add(String line) {
        if (line == null) return;
        Log.i(TAG, line);
        synchronized (lines) {
            // 时间戳只取后 5 位：同一次联调里够区分先后，又不会把每行挤掉半屏
            String stamp = Long.toString(System.currentTimeMillis() / 1000 % 100000);
            lines.addLast(stamp + " " + line);
            while (lines.size() > MAX_LINES) {
                lines.removeFirst();
            }
        }
        final Listener target = listener;
        if (target != null) {
            main.post(() -> {
                Listener current = listener;
                if (current != null) current.onLogChanged();
            });
        }
    }

    /** 当前全部日志，每行一条。 */
    public String text() {
        synchronized (lines) {
            StringBuilder builder = new StringBuilder();
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                builder.append(iterator.next());
                if (iterator.hasNext()) builder.append('\n');
            }
            return builder.toString();
        }
    }

    public void clear() {
        synchronized (lines) {
            lines.clear();
        }
        Listener target = listener;
        if (target != null) target.onLogChanged();
    }

    /** 日志页进来时挂上、走时摘掉。只留一个：同时只有一页在看它。 */
    public void setListener(Listener value) {
        listener = value;
    }
}
