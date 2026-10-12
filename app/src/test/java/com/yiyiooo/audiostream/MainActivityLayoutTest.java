package com.yiyiooo.audiostream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 布局的回归测试：在 JVM 上把每个布局真 inflate 一遍。
 *
 * 为什么必须留在 JVM 上：这台开发机是 VirtualBox 里的 Android-X86，图形栈会成片卡死，
 * uiautomator 抓到的是 System UI 自己的 ANR 弹窗而不是本应用的界面，界面改坏了根本看不出来。
 * 而「资源名写错、Material 样式找不到、id 拼错、控件漏了」恰恰是最常见的低级错误，
 * 它们不需要屏幕就能验——inflate 一次就会炸出来。
 *
 * 它验不了「好不好看」，那只能靠人眼。这里只钉住结构与关键文案。
 */
@RunWith(RobolectricTestRunner.class)
public class MainActivityLayoutTest {

    private View inflate(int layoutId) {
        Context base = org.robolectric.RuntimeEnvironment.getApplication();
        // 用应用主题包一层：Material 组件要求主题里有 Material3 的属性，
        // 直接拿裸 Context 会以「找不到 attr/colorPrimary」之类的形式失败
        Context themed = new ContextThemeWrapper(base, R.style.Theme_AudioStream);
        return LayoutInflater.from(themed).inflate(layoutId, null);
    }

    /**
     * 按名字查一个控件 id。控件已经被删掉时返回 0——正好用来断言「它不该再存在」。
     *
     * 不能直接写 R.id.xxx：那个控件删掉之后 R.id 里就没有它了，引用会直接编译不过。
     * 而这几条断言的意义恰恰是防回退，所以只能按名字查。
     */
    private int idOrZero(String name) {
        return org.robolectric.RuntimeEnvironment.getApplication().getResources()
                .getIdentifier(name, "id", "com.yiyiooo.audiostream");
    }

    // ========== 主壳子 ==========

    @Test
    public void 主界面是顶栏加内容区加底部页签() {
        View root = inflate(R.layout.activity_main);
        assertNotNull("顶栏", root.findViewById(R.id.toolbar));
        assertNotNull("内容区", root.findViewById(R.id.fragmentContainer));
        assertNotNull("底部页签", root.findViewById(R.id.bottomNav));
    }

    @Test
    public void 底部页签恰好是播放共享设置日志四个() {
        // 页签从 Material 的 BottomNavigationView 换成了自绘的四个等宽文字（用户要求不要图标），
        // 所以这里不能再断言 getMenu()——它是 BottomNavigationView 才有的东西。
        // 改成直接数子视图并逐位核对 id：这样连顺序一起钉住，光数个数是钉不住的。
        View root = inflate(R.layout.activity_main);
        android.view.ViewGroup nav = root.findViewById(R.id.bottomNav);
        assertEquals("页签数量", 4, nav.getChildCount());
        assertEquals("第 1 个页签", R.id.tabPlayback, nav.getChildAt(0).getId());
        assertEquals("第 2 个页签", R.id.tabShare, nav.getChildAt(1).getId());
        assertEquals("第 3 个页签", R.id.tabSettings, nav.getChildAt(2).getId());
        assertEquals("第 4 个页签", R.id.tabLog, nav.getChildAt(3).getId());

        for (int i = 0; i < nav.getChildCount(); i++) {
            android.view.View tab = nav.getChildAt(i);
            org.junit.Assert.assertTrue("第 " + (i + 1) + " 个页签应当是纯文字标签",
                    tab instanceof android.widget.TextView);
            org.junit.Assert.assertTrue("第 " + (i + 1) + " 个页签应当显示文字",
                    ((android.widget.TextView) tab).getText().length() > 0);
        }
    }

    // ========== 播放页 ==========

    @Test
    public void 播放页有地址输入查找与添加入口() {
        View root = inflate(R.layout.fragment_playback);
        assertNotNull("地址输入框", root.findViewById(R.id.hostInput));
        assertNotNull("查找按钮", root.findViewById(R.id.discoverButton));
        assertNotNull("设备下拉", root.findViewById(R.id.deviceSpinner));
        assertNotNull("添加按钮", root.findViewById(R.id.addRouteButton));
        assertNotNull("播放列表容器", root.findViewById(R.id.routeContainer));
    }

    @Test
    public void 播放页不再有单路时代的连接按钮() {
        // 防回退：单路那会儿是一个「连接」按钮加一根全局音量滑条，
        // 它们一旦回来了就说明拆分没拆干净
        View root = inflate(R.layout.fragment_playback);
        assertEquals("旧的连接按钮不该还在", 0, idOrZero("connectButton"));
        assertEquals("旧的全局音量滑条不该还在", 0, idOrZero("volumeSlider"));
        assertNotNull(root);
    }

    // ========== 每一路那张卡片 ==========

    @Test
    public void 每路卡片有停止编辑删除三个操作() {
        View root = inflate(R.layout.item_route);
        MaterialButton toggle = root.findViewById(R.id.routeToggle);
        MaterialButton edit = root.findViewById(R.id.routeEdit);
        MaterialButton delete = root.findViewById(R.id.routeDelete);
        assertNotNull("停止/启动按钮", toggle);
        assertNotNull("编辑按钮", edit);
        assertNotNull("删除按钮", delete);
        // 三个操作都要能被看见，不能收进溢出菜单——用户明确要求能停、能改、能删
        assertEquals("停止", toggle.getText().toString());
        assertEquals("编辑", edit.getText().toString());
        assertEquals("删除", delete.getText().toString());
    }

    @Test
    public void 每路卡片的音量可以放大到两百() {
        View root = inflate(R.layout.item_route);
        Slider volume = root.findViewById(R.id.routeVolume);
        assertEquals("下限", 0f, volume.getValueFrom(), 1e-6f);
        assertEquals("上限要能放大到 200%", 2f, volume.getValueTo(), 1e-6f);
        assertEquals("默认 100%", 1f, volume.getValue(), 1e-6f);
    }

    @Test
    public void 卡片上不再摆缓冲与欠载这类给排错看的数字() {
        // 用户明确说过「无意义的提示性文字太多」。这些计数对普通用户没有意义，
        // 线索留在日志页；它们一旦回到卡片上就说明又把界面塞满了
        assertEquals("不该再有计数行", 0, idOrZero("routeCounters"));
        assertNotNull(inflate(R.layout.item_route));
    }

    // ========== 共享页 ==========

    @Test
    public void 共享页有开启共享与录制两个按钮和地址大标题() {
        View root = inflate(R.layout.fragment_share);
        assertNotNull("开启共享", root.findViewById(R.id.sourceButton));
        assertNotNull("录制手机声音", root.findViewById(R.id.captureButton));
        assertNotNull("本机地址", root.findViewById(R.id.localAddressText));
        assertNotNull("共享状态", root.findViewById(R.id.sourceStatusText));
        assertNotNull("录制状态", root.findViewById(R.id.captureStatusText));
    }

    @Test
    public void 共享页保留在电脑上填地址的引导() {
        // 这页唯一要用户做的事就是「把地址填到电脑那边」，那句引导删了用户就得自己猜
        View root = inflate(R.layout.fragment_share);
        TextView label = root.findViewById(R.id.shareAddressLabel);
        assertTrue("要有填地址的引导",
                label.getText().toString().contains("电脑"));
    }

    // ========== 设置页 ==========

    @Test
    public void 设置页外观三选且恰好三个() {
        View root = inflate(R.layout.fragment_settings);
        com.google.android.material.button.MaterialButtonToggleGroup group =
                root.findViewById(R.id.themeGroup);
        assertNotNull(group);
        assertEquals("外观选项数量", 3, group.getChildCount());
        assertNotNull(root.findViewById(R.id.themeSystem));
        assertNotNull(root.findViewById(R.id.themeLight));
        assertNotNull(root.findViewById(R.id.themeDark));
        assertNotNull("本机身份", root.findViewById(R.id.identityText));
    }

    // ========== 日志页 ==========

    @Test
    public void 日志页只有日志与清空() {
        View root = inflate(R.layout.fragment_log);
        assertNotNull("日志正文", root.findViewById(R.id.logText));
        assertNotNull("可滚动", root.findViewById(R.id.logScroll));
        assertNotNull("清空按钮", root.findViewById(R.id.clearLogButton));
    }

    // ========== 修改对话框 ==========

    @Test
    public void 修改一路的对话框有地址与设备两样() {
        View root = inflate(R.layout.dialog_edit_route);
        assertNotNull("地址", root.findViewById(R.id.editHostInput));
        assertNotNull("查找", root.findViewById(R.id.editFindButton));
        assertNotNull("设备下拉", root.findViewById(R.id.editDeviceSpinner));
    }

    // ========== 文案 ==========

    @Test
    public void 播放页不再出现输出输入这种术语() {
        View root = inflate(R.layout.fragment_playback);
        assertEquals("播放页只有两层布局，直接扫所有文本",
                0, countTextContaining(root, "[输出]"));
        assertEquals(0, countTextContaining(root, "[输入]"));
    }

    private static int countTextContaining(View view, String needle) {
        int found = 0;
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            if (text.contains(needle)) found++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                found += countTextContaining(group.getChildAt(i), needle);
            }
        }
        return found;
    }

    /** 这个断言的存在只为一个理由：气泡式的说明小字不能再回来。 */
    @Test
    public void 播放页没有解释性小字() {
        View root = inflate(R.layout.fragment_playback);
        assertEquals(0, countTextContaining(root, "输出＝"));
        assertEquals(0, countTextContaining(root, "不影响系统媒体音量"));
        assertEquals(0, countTextContaining(root, "排查用"));
        assertTrue("列表为空时要给一句人话", root.findViewById(R.id.routesEmptyText) instanceof TextView);
    }
}
