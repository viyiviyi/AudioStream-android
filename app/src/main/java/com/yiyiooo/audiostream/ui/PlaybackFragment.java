package com.yiyiooo.audiostream.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.yiyiooo.audiostream.AppConfig;
import com.yiyiooo.audiostream.AudioStreamService;
import com.yiyiooo.audiostream.MainActivity;
import com.yiyiooo.audiostream.R;
import com.yiyiooo.audiostream.discovery.DeviceInfo;
import com.yiyiooo.audiostream.discovery.PcInfo;
import com.yiyiooo.audiostream.pull.PullState;
import com.yiyiooo.audiostream.route.PlaybackRoute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 播放页：上面添一路，下面列出每一路，每路自己一根音量滑条。
 *
 * 每一路有三个操作，语义分得清清楚楚：
 * <ul>
 *   <li><b>停止</b>：断开连接，但记录留着，按钮变成「启动」，随时能接着听。静态时不该逼用户删掉重配。</li>
 *   <li><b>编辑</b>：改这一路连的是哪台电脑、哪块设备。改完按新配置重连。</li>
 *   <li><b>删除</b>：把这一路从列表里去掉了，会先问一句。</li>
 * </ul>
 *
 * 卡片里的控件 id 是重复的（有几路就有几份），所以一律从卡片自己的根视图上找；
 * 拿 Fragment 的 view 去找只会拿到第一张卡片的控件，那会让所有路的音量都调到第一路去。
 */
public class PlaybackFragment extends Fragment implements MainActivity.ServiceAware {

    private static final String TAG = "AudioStream";

    /** 与布局里 Slider 的 android:stepSize 必须一致，见 snapVolume。 */
    private static final float VOLUME_STEP = 0.05f;

    private TextInputLayout hostLayout;
    private TextInputEditText hostInput;
    private Spinner deviceSpinner;
    private MaterialButton addRouteButton;
    private TextView routesTitle;
    private TextView routesEmptyText;
    private LinearLayout routeContainer;

    private final List<DeviceInfo> devices = new ArrayList<>();
    private final Map<String, RouteCard> cards = new HashMap<>();

    /**
     * 编辑对话框正开着时的那些控件与它自己的设备列表。
     *
     * 编辑用的是对话框里那份设备表，与上面「添一路」那份分开：用户在对话框里点查找，
     * 结果该落在对话框里，不该把上面那份也换掉——他可能正在用上面那份准备添一路。
     */
    private String editingRouteId;
    private Spinner editSpinner;
    private final List<DeviceInfo> editDevices = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        hostLayout = view.findViewById(R.id.hostLayout);
        hostInput = view.findViewById(R.id.hostInput);
        deviceSpinner = view.findViewById(R.id.deviceSpinner);
        addRouteButton = view.findViewById(R.id.addRouteButton);
        routesTitle = view.findViewById(R.id.routesTitle);
        routesEmptyText = view.findViewById(R.id.routesEmptyText);
        routeContainer = view.findViewById(R.id.routeContainer);

        setSpinner(deviceSpinner, devices, "先点上面的「查找声音」");
        addRouteButton.setEnabled(false);

        view.findViewById(R.id.discoverButton).setOnClickListener(v -> onDiscover());
        addRouteButton.setOnClickListener(v -> onAddRoute());

        hostInput.setText(AppConfig.loadLastHost(requireContext()));
        renderRoutes();
    }

    @Override
    public void onServiceChanged() {
        updateAddButtonState();
        renderRoutes();
    }

    @Override
    public void onRoutesChanged() {
        updateAddButtonState();
        renderRoutes();
    }

    @Override
    public void onDevices(List<DeviceInfo> list, PcInfo info) {
        // 编辑对话框开着时，这次查找是它发起的，结果填进它自己的下拉
        if (editingRouteId != null && editSpinner != null) {
            editDevices.clear();
            if (list != null) editDevices.addAll(list);
            setSpinner(editSpinner, editDevices,
                    editDevices.isEmpty() ? "这台电脑上没有找到可用的声音" : "选择声音");
            return;
        }

        devices.clear();
        if (list != null) devices.addAll(list);
        // 输出在前、输入在后：用户最常要的是「电脑正在放的声音」
        List<DeviceInfo> ordered = new ArrayList<>();
        for (DeviceInfo d : devices) if (d.isOutput()) ordered.add(d);
        for (DeviceInfo d : devices) if (d.isInput()) ordered.add(d);
        devices.clear();
        devices.addAll(ordered);

        setSpinner(deviceSpinner, devices,
                devices.isEmpty() ? "这台电脑上没有找到可用的声音" : "选择声音");
        updateAddButtonState();

        String who = info == null ? "" : (info.pcName().isEmpty() ? info.machineId() : info.pcName());
        log("找到 " + devices.size() + " 个声音" + (who.isEmpty() ? "" : "（" + who + "）"));
    }

    @Override
    public void onDiscoveryFailed(String reason) {
        hostLayout.setError(reason);
        log("查找声音失败：" + reason);
    }

    // ========== 添一路 ==========

    private void onDiscover() {
        String host = text(hostInput).trim();
        hostLayout.setError(null);
        if (host.isEmpty()) {
            hostLayout.setError("先填电脑的地址");
            return;
        }
        if (service() == null) return;
        service().discover(host);
    }

    private void onAddRoute() {
        hostLayout.setError(null);
        String host = text(hostInput).trim();
        if (host.isEmpty()) {
            hostLayout.setError("先填电脑的地址");
            return;
        }
        AudioStreamService svc = service();
        if (svc == null) return;
        int index = deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= devices.size()) {
            hostLayout.setError("先点「查找声音」，再选一个");
            return;
        }
        DeviceInfo device = devices.get(index);
        activity().ensureForegroundService();
        String error = svc.addRoute(host, device.id(), device.name());
        if (error != null) {
            hostLayout.setError(error);
            log("添加失败：" + error);
            return;
        }
        log("添加了一路：" + device.displayName());
        renderRoutes();
    }

    private void updateAddButtonState() {
        if (addRouteButton == null) return;
        // 服务就绪就必须把按钮打开：onViewCreated 里第一次调用时服务还没绑上，那时按钮是灰的；
        // 这里若不显式打开，它会一直灰着——用户点了没反应，而界面上看不出任何原因
        addRouteButton.setEnabled(service() != null);
    }

    // ========== 列表 ==========

    /**
     * 按服务报上来的那几路重画列表。
     *
     * 已有的卡片连着控件一起留着、只更新内容——整块清掉重建会有两个后果：用户正在拖的音量滑条
     * 会被打断，而且滚动位置会跳回顶部。
     */
    private void renderRoutes() {
        AudioStreamService svc = service();
        if (svc == null || routeContainer == null) return;
        List<PlaybackRoute> routes = svc.routes();

        Set<String> alive = new HashSet<>();
        for (PlaybackRoute route : routes) alive.add(route.id());

        Iterator<Map.Entry<String, RouteCard>> iterator = cards.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, RouteCard> entry = iterator.next();
            if (!alive.contains(entry.getKey())) {
                routeContainer.removeView(entry.getValue().root);
                iterator.remove();
            }
        }

        for (PlaybackRoute route : routes) {
            RouteCard card = cards.get(route.id());
            if (card == null) {
                card = createCard(route);
                cards.put(route.id(), card);
                routeContainer.addView(card.root);
            }
            card.route = route;
            card.bind();
        }

        routesEmptyText.setVisibility(routes.isEmpty() ? View.VISIBLE : View.GONE);
        setIfChanged(routesTitle, routes.isEmpty() ? "正在播放" : "正在播放（" + routes.size() + "）");
    }

    private RouteCard createCard(PlaybackRoute route) {
        View root = getLayoutInflater().inflate(R.layout.item_route, routeContainer, false);
        RouteCard card = new RouteCard(root, route.id());

        card.volume.addOnChangeListener((slider, value, fromUser) -> {
            // fromUser 为假是代码设值（bind 里对齐配置），那时不该回写服务——
            // 否则会变成「服务说 0.3 → 界面设 0.3 → 回写 0.3」，白转一圈还可能打断用户
            if (!fromUser) return;
            card.volumeText.setText(percent(value));
            if (service() != null) service().setRouteVolume(card.id, value);
        });
        card.volume.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                card.tracking = true;
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                card.tracking = false;
            }
        });

        card.toggle.setOnClickListener(v -> {
            PlaybackRoute current = card.route;
            if (current == null || service() == null) return;
            boolean toPause = !current.isPaused();
            service().setRoutePaused(card.id, toPause);
            log((toPause ? "停止：" : "启动：") + current.config().sourceLabel());
        });
        card.edit.setOnClickListener(v -> showEditDialog(card.route));
        card.delete.setOnClickListener(v -> confirmDelete(card.route));
        return card;
    }

    // ========== 编辑与删除 ==========

    private void showEditDialog(PlaybackRoute route) {
        if (route == null || service() == null) return;

        View content = getLayoutInflater().inflate(R.layout.dialog_edit_route, null);
        TextInputLayout editHostLayout = content.findViewById(R.id.editHostLayout);
        TextInputEditText editHostInput = content.findViewById(R.id.editHostInput);
        MaterialButton findButton = content.findViewById(R.id.editFindButton);
        Spinner spinner = content.findViewById(R.id.editDeviceSpinner);

        editHostInput.setText(route.config().host());
        editingRouteId = route.id();
        editSpinner = spinner;
        editDevices.clear();
        // 把这一路现在用的设备先摆进下拉，用户不改设备时点保存就是就地生效；
        // 换电脑或换设备则先点一次「查找声音」
        editDevices.add(new DeviceInfo(route.config().deviceName(),
                route.config().deviceId(), false, DeviceInfo.FLOW_OUTPUT));
        setSpinner(spinner, editDevices, "选择声音");

        findButton.setOnClickListener(v -> {
            String host = text(editHostInput).trim();
            if (host.isEmpty()) {
                editHostLayout.setError("先填电脑的地址");
                return;
            }
            editHostLayout.setError(null);
            if (service() != null) service().discover(host);
        });

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("修改这一路")
                .setView(content)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        // 正按钮的监听在 show 之后覆写，这样校验不过时对话框不关——
        // setPositiveButton 传进去的监听无论如何都会关掉对话框
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String host = text(editHostInput).trim();
                    if (host.isEmpty()) {
                        editHostLayout.setError("先填电脑的地址");
                        return;
                    }
                    int index = spinner.getSelectedItemPosition();
                    if (index < 0 || index >= editDevices.size()) {
                        editHostLayout.setError("先点「查找声音」，再选一个");
                        return;
                    }
                    DeviceInfo device = editDevices.get(index);
                    String error = service() == null ? "服务还没就绪"
                            : service().updateRoute(editingRouteId, host, device.id(), device.name());
                    if (error != null) {
                        editHostLayout.setError(error);
                        return;
                    }
                    log("已修改：" + device.displayName());
                    finishEditing();
                    dialog.dismiss();
                }));
        dialog.setOnDismissListener(d -> finishEditing());
        dialog.show();
    }

    /** 对话框不管是怎么关掉的，都要把「正在编辑」这个状态收干净，否则下次查找会填错地方。 */
    private void finishEditing() {
        editingRouteId = null;
        editSpinner = null;
    }

    private void confirmDelete(PlaybackRoute route) {
        if (route == null || service() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("删除这一路？")
                .setMessage(route.config().sourceLabel())
                .setPositiveButton("删除", (d, w) -> {
                    service().removeRoute(route.id());
                    log("已删除：" + route.config().sourceLabel());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ========== 卡片 ==========

    /** 屏幕上的一路。它拿着这一路的运行时对象与那几个控件，负责把状态照实显示。 */
    private final class RouteCard {
        final View root;
        final String id;
        final TextView title;
        final TextView state;
        final TextView detail;
        final TextView volumeText;
        final Slider volume;
        final MaterialButton toggle;
        final MaterialButton edit;
        final MaterialButton delete;

        PlaybackRoute route;

        /** 用户此刻正按着这根滑条。按着的时候不去动它，免得跟他的手指打架。 */
        boolean tracking;

        RouteCard(View root, String id) {
            this.root = root;
            this.id = id;
            this.title = root.findViewById(R.id.routeTitle);
            this.state = root.findViewById(R.id.routeState);
            this.detail = root.findViewById(R.id.routeDetail);
            this.volumeText = root.findViewById(R.id.routeVolumeText);
            this.volume = root.findViewById(R.id.routeVolume);
            this.toggle = root.findViewById(R.id.routeToggle);
            this.edit = root.findViewById(R.id.routeEdit);
            this.delete = root.findViewById(R.id.routeDelete);
        }

        void bind() {
            PlaybackRoute current = route;
            if (current == null) return;

            setIfChanged(title, current.config().sourceLabel());
            setIfChanged(state, current.isPaused() ? "已停止" : current.state().friendlyLabel());
            setIfChanged(toggle, current.isPaused() ? "启动" : "停止");

            // 只有真的出问题时才多出一行原因。正常时那一行的内容与状态重复，白占地方
            String note = current.stateDetail();
            boolean bad = current.state() == PullState.FAILED;
            if (bad && note != null && !note.isEmpty()) {
                setIfChanged(detail, note);
                detail.setVisibility(View.VISIBLE);
            } else {
                detail.setVisibility(View.GONE);
            }

            if (!tracking) {
                float value = snapVolume(current.config().volume());
                // 只在真的不一样时设：Slider.setValue 会让它自己重绘并触发一次监听
                if (Math.abs(volume.getValue() - value) > 1e-4f) {
                    volume.setValue(value);
                }
                setIfChanged(volumeText, percent(value));
            }
        }
    }

    // ========== 小工具 ==========

    private MainActivity activity() {
        return (MainActivity) requireActivity();
    }

    private AudioStreamService service() {
        MainActivity host = (MainActivity) getActivity();
        return host == null ? null : host.service();
    }

    private void log(String line) {
        MainActivity host = (MainActivity) getActivity();
        if (host != null) host.log(line);
    }

    /** 设备下拉：把当前这份设备表摆上去，空的时候给一句说明而不是一个空下拉。 */
    private void setSpinner(Spinner spinner, List<DeviceInfo> list, String emptyHint) {
        List<String> labels = new ArrayList<>();
        if (list.isEmpty()) {
            labels.add(emptyHint);
        } else {
            for (DeviceInfo device : list) {
                // 前缀是给用户挑声音用的分类：「输出/输入」得先想一下是谁的口，
                // 而「播放设备」就是这台电脑正在放的声音、「麦克风」就是它的麦克风。
                // 只在给人挑的地方加这个前缀——同一个名字写进日志时不需要它。
                //
                // 这个前缀在多页重构时丢过一次，用户随即反馈「来源设备要区分播放设备和麦克风」。
                // 判断用 isInput() 而不是 isOutput()：flow 认不出来时的默认值是 output（见 DeviceInfo），
                // 所以「不是输入」就该按播放设备显示，反过来会把认不出的设备错标成麦克风。
                labels.add((device.isInput() ? "[麦克风] " : "[播放设备] ") + device.displayName());
            }
        }
        spinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, labels));
    }

    private static String text(TextInputEditText view) {
        return view.getText() == null ? "" : view.getText().toString();
    }

    private static String percent(float value) {
        return Math.round(value * 100) + "%";
    }

    /**
     * 把音量对到滑条的刻度上。
     *
     * Slider 要求设进去的值正好落在 stepSize 的整数倍上，否则直接抛。
     * 存下来的偏好可能来自旧版本或被手改过，所以在这里量化一次——崩溃比音量差一格严重得多。
     */
    private static float snapVolume(float value) {
        float snapped = Math.round(value / VOLUME_STEP) * VOLUME_STEP;
        return Math.max(0f, Math.min(2f, snapped));
    }

    private static void setIfChanged(TextView view, String text) {
        if (view != null && !text.contentEquals(view.getText())) view.setText(text);
    }

    private static void setIfChanged(MaterialButton view, String text) {
        if (view != null && !text.contentEquals(view.getText())) view.setText(text);
    }
}
