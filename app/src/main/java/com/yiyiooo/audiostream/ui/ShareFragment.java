package com.yiyiooo.audiostream.ui;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.yiyiooo.audiostream.AudioStreamService;
import com.yiyiooo.audiostream.MainActivity;
import com.yiyiooo.audiostream.R;

/**
 * 共享页：把这台手机变成一台能被电脑拉取的音源。
 *
 * 原来它和播放端挤在一屏里，而两件事其实没有关系——只想听电脑声音的人不该被这些开关挡住。
 */
public class ShareFragment extends Fragment implements MainActivity.ServiceAware {

    private TextView localAddressText;
    private TextView sourceStatusText;
    private TextView captureStatusText;
    private MaterialButton sourceButton;
    private MaterialButton captureButton;

    /**
     * 要录音权限。拿到之后顺势把共享开起来——用户点的是「开启共享」，
     * 权限只是流程里的一道，不该让他为同一件事点两次。
     */
    private final ActivityResultLauncher<String> recordPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                MainActivity activity = (MainActivity) getActivity();
                if (activity == null) return;
                activity.log(granted ? "已允许录音" : "没有录音权限，手机发不出声音");
                if (granted) {
                    startSharing();
                }
                refresh();
            });

    /**
     * 请系统弹一次「录制手机声音」的授权框。这个框**每次都要弹**：Android 不允许应用自己决定
     * 能否录内音频，拿到的凭据也只在本进程活着期间有效（存不了盘），所以做不到授权一次永久可用。
     */
    private final ActivityResultLauncher<Intent> capturePermission =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                MainActivity activity = (MainActivity) getActivity();
                if (activity == null || activity.service() == null) return;
                // 取消时 resultCode 不是 RESULT_OK。必须如实带给服务：不能因为 data 非空就当成成功，
                // 否则电脑端点单会走到「系统没给出内录通道」那种没法解释的失败
                activity.service().setCaptureGrant(result.getResultCode(), result.getData());
                boolean ok = activity.service().hasCaptureGrant();
                activity.log(ok ? "已允许录制手机声音" : "授权被取消了");
                refresh();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_share, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        localAddressText = view.findViewById(R.id.localAddressText);
        sourceStatusText = view.findViewById(R.id.sourceStatusText);
        captureStatusText = view.findViewById(R.id.captureStatusText);
        sourceButton = view.findViewById(R.id.sourceButton);
        captureButton = view.findViewById(R.id.captureButton);
        view.findViewById(R.id.shareDeviceText).setVisibility(View.GONE);

        sourceButton.setOnClickListener(v -> onSourceToggle());
        captureButton.setOnClickListener(v -> onCaptureRequest());
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onServiceChanged() {
        refresh();
    }

    @Override
    public void onRoutesChanged() {
        // 共享页本身不受播放列表影响，但电脑来连的状态会变，顺手刷一次
        refresh();
    }

    // ========== 操作 ==========

    private void onSourceToggle() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || activity.service() == null) return;
        if (activity.service().sourceRunning()) {
            activity.service().stopSource();
            activity.log("已停止共享");
            refresh();
            return;
        }
        if (!activity.service().hasRecordPermission() && !activity.service().hasCaptureGrant()) {
            // 两个来源一个都用不了。先要录音权限：它能被永久授予，比内录那个每次都要弹框的更该先试；
            // 已经授权过内录的用户不该在这里被拦住。
            recordPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startSharing();
    }

    private void startSharing() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || activity.service() == null) return;
        // 共享要在后台持续送声音（锁屏也不能断），所以要进前台
        activity.ensureForegroundService();
        String error = activity.service().startSource();
        activity.log(error == null ? "已开启共享，电脑可以来连了" : "开启共享失败：" + error);
        refresh();
    }

    private void onCaptureRequest() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || activity.service() == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            captureStatusText.setText("这台手机的 Android 版本太老，录制手机声音需要 Android 10 及以上");
            return;
        }
        android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager)
                        activity.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            captureStatusText.setText("这台设备不支持录制手机声音");
            return;
        }
        try {
            capturePermission.launch(manager.createScreenCaptureIntent());
        } catch (Exception e) {
            // 部分定制系统会把这一步拦下来（缺少系统组件、被安全策略禁止）
            captureStatusText.setText("打不开授权框：" + e);
            activity.log("打不开内录授权框：" + e);
        }
    }

    // ========== 显示 ==========

    private void refresh() {
        if (sourceButton == null) return;
        MainActivity activity = (MainActivity) getActivity();
        AudioStreamService service = activity == null ? null : activity.service();

        if (service == null) {
            sourceButton.setText("开启共享");
            captureButton.setText("录制手机正在放的声音");
            localAddressText.setText("—");
            sourceStatusText.setText("正在准备…");
            captureStatusText.setText("");
            return;
        }

        boolean running = service.sourceRunning();
        sourceButton.setText(running ? "停止共享" : "开启共享");

        String addresses = service.localAddresses();
        localAddressText.setText(addresses.isEmpty() ? "没连上网" : addresses);

        if (running) {
            int sessions = service.sourceSessionCount();
            sourceStatusText.setText(sessions == 0
                    ? "共享中 · 还没有电脑连过来"
                    : "共享中 · " + sessions + " 台电脑正在播放这台手机的声音");
        } else {
            sourceStatusText.setText("未开启");
        }

        captureButton.setText(service.hasCaptureGrant() ? "重新授权" : "录制手机正在放的声音");
        captureStatusText.setText(service.hasCaptureGrant()
                ? "已允许 · 电脑可以拉取手机正在放的声音"
                : "未允许 · 想共享手机正在放的音乐时点上面的按钮");
    }
}
