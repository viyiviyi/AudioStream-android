package com.yiyiooo.audiostream;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.yiyiooo.audiostream.discovery.DeviceInfo;
import com.yiyiooo.audiostream.discovery.PcInfo;
import com.yiyiooo.audiostream.ui.LogFragment;
import com.yiyiooo.audiostream.ui.PlaybackFragment;
import com.yiyiooo.audiostream.ui.SettingsFragment;
import com.yiyiooo.audiostream.ui.ShareFragment;

/**
 * 主界面：只做三件事——顶栏、底部四个页签的切换、以及把前台服务绑起来给各页共用。
 *
 * 拆页之前这里塞了七百多行，播放、共享、外观、日志全在一屏里。现在各页的逻辑在各自的
 * Fragment 里，这里不再出现任何一项业务概念（不碰播放列表、不认识来源端、不知道主题）。
 *
 * 服务只在这里绑一次：四个页要的是同一个服务实例，各页自己绑会在切页时反复解绑重绑，
 * 而解绑时服务可能正好被系统回收，用户看到的是「切了个页，播放就断了」。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioStream";

    /** 四个页签的 id 与它们对应的标题。顺序即界面上从左到右的顺序。 */
    private static final int[] TAB_IDS = {
            R.id.tabPlayback, R.id.tabShare, R.id.tabSettings, R.id.tabLog
    };

    /**
     * 当前这一页想收到服务的通知。四个方法都有默认实现——各页只覆写自己关心的那几个，
     * 音量页不需要知道设备发现的结果，日志页什么都不需要关心。
     */
    public interface ServiceAware {
        /** 服务刚绑上或断开了。 */
        default void onServiceChanged() {
        }

        /** 播放列表有变化：加了、删了、停了、或者某一路的状态往前走了。 */
        default void onRoutesChanged() {
        }

        /** 设备列表查回来了。 */
        default void onDevices(java.util.List<DeviceInfo> devices, PcInfo info) {
        }

        /** 查设备失败了，原因已经是中文。 */
        default void onDiscoveryFailed(String reason) {
        }
    }

    private AudioStreamService service;
    private boolean bound;

    private MaterialToolbar toolbar;

    /** 当前显示的是哪一页。重复点同一个页签时用它判断「不必再切一次」。 */
    private int currentTab;

    /** 服务回调要转给「当前显示的那一页」，所以在这个壳子里收口一次。 */
    private final AudioStreamService.Listener serviceListener = new AudioStreamService.Listener() {
        @Override
        public void onRoutesChanged() {
            each(ServiceAware::onRoutesChanged);
        }

        @Override
        public void onDevices(java.util.List<DeviceInfo> devices, PcInfo info) {
            each(aware -> aware.onDevices(devices, info));
        }

        @Override
        public void onDiscoveryFailed(String reason) {
            each(aware -> aware.onDiscoveryFailed(reason));
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AudioStreamService.LocalBinder) binder).service();
            // 监听者只能有一个，所以由壳子持有并往下分发；各页自己设会把别人的顶掉
            service.setListener(serviceListener);
            bound = true;
            Log.i(TAG, "服务已连上");
            each(ServiceAware::onServiceChanged);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            Log.w(TAG, "服务断开（进程多半被系统收了）");
            each(ServiceAware::onServiceChanged);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        MachineIdentity identity = MachineIdentity.load(this);
        toolbar.setSubtitle("v" + BuildConfig.VERSION_NAME + "　·　" + identity.name());

        for (int id : TAB_IDS) {
            findViewById(id).setOnClickListener(v -> selectTab(id));
        }

        // 只在第一次进来时选默认页；旋转屏幕后由 FragmentManager 自己恢复，不必再切一次
        if (savedInstanceState == null) {
            selectTab(R.id.tabPlayback);
        } else {
            currentTab = R.id.tabPlayback;
        }

        // 只绑定、不启动前台服务：打开界面这一刻主线程正在画首帧，而 startForegroundService
        // 给的 5 秒期限从那一刻起算，慢设备上首帧出不来就是 ANR（界面从此起不来的那种）。
        // 真正需要常驻是在开始播放或开启共享时，见 ensureForegroundService。
        boolean bindOk = bindService(new Intent(this, AudioStreamService.class),
                connection, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "bindService -> " + bindOk);
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    // ========== 给各页用的 ==========

    /** 当前的服务实例；还没绑上时为 null，各页要按「暂时没有」处理而不是崩。 */
    public AudioStreamService service() {
        return service;
    }

    /** 记一条日志。走全应用共用的那一条，切到日志页能看到不管在哪一页发生的事。 */
    public void log(String line) {
        AppLog.get().add(line);
    }

    /**
     * 把服务转成前台。开始播放或开启共享时才调——那之后进程要能在后台活着，
     * 而这一刻界面早已画完、主线程空闲，能满足 5 秒的期限。
     */
    public void ensureForegroundService() {
        Intent intent = new Intent(this, AudioStreamService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    // ========== 内部 ==========

    /**
     * 把一件事通知给当前活着的页。
     *
     * 从 FragmentManager 现查而不是记一个字段：旋转屏幕或系统回收后重建时，
     * 真正活着的是 FragmentManager 恢复出来的那一个，字段里的引用会过期。
     */
    private void each(java.util.function.Consumer<ServiceAware> action) {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof ServiceAware) action.accept((ServiceAware) fragment);
        }
    }

    /**
     * 切到某一页。
     *
     * 选中态同时用颜色与字重表示：只靠颜色的话，在深色主题下「选中」与「未选中」的差别
     * 可能小到看不出来，而页签只有文字，没有图标可以承载这个区别。
     */
    private void selectTab(int tabId) {
        for (int id : TAB_IDS) {
            TextView tab = findViewById(id);
            boolean selected = id == tabId;
            tab.setSelected(selected);
            tab.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }

        // 重复点当前页签不再 replace 一次：那会把这一页整个重建，用户填了一半的地址就没了
        if (tabId == currentTab && getSupportFragmentManager().getFragments().size() > 0) {
            return;
        }
        currentTab = tabId;

        Fragment target;
        String title;
        if (tabId == R.id.tabShare) {
            target = new ShareFragment();
            title = "共享";
        } else if (tabId == R.id.tabSettings) {
            target = new SettingsFragment();
            title = "设置";
        } else if (tabId == R.id.tabLog) {
            target = new LogFragment();
            title = "日志";
        } else {
            target = new PlaybackFragment();
            title = "播放";
        }
        toolbar.setTitle(title);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, target)
                .commit();
    }
}
