package com.yiyiooo.audiostream;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * 把「每行内容离屏幕边缘的宽度要一致」变成可量、可回归的断言。
 *
 * 为什么值得单独写一个测试：界面不整齐这种事，眼睛看得出来、编译器与普通单元测试都管不着，
 * 于是每次改布局都可能悄悄退回原样（这个工程就退过一次：容器 12dp、卡片 16dp、卡片外的小标题
 * 又额外缩进 4dp，同一页里文字左边缘一会儿 16dp 一会儿 28dp）。有了坐标断言，谁再随手加一个
 * 边距都会在这里被拦住。
 *
 * 量的是**文字的左边缘**而不是控件的左边缘：用户看到的是文字。
 *
 * 有三类东西不参与这条对齐线，这是量过之后才定下来的口径：
 *   - 输入框：它有一圈描边与内部留白，文字本来就不在正文那条线上；
 *   - 按钮：它是一块有形状的控件，文字在按钮内部居中。**MaterialButton 本身就是 TextView 的子类**，
 *     一开始没排除它，于是「按钮的 paddingLeft」被当成偏差报了出来；
 *   - 并排（水平容器）里的元素：同一行里并排放的东西本来就不共用一条左对齐线，
 *     例如音量行里右边那个百分比数字，它按设计就在最右边。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-xhdpi")
public class LayoutAlignmentTest {

    /** 容差 1px：像素取整会把同一个 dp 值算出一两个像素的差，超过 1px 就说明边距真的不同。 */
    private static final int TOLERANCE_PX = 1;

    private static int screenWidth() {
        return (int) (360 * RuntimeEnvironment.getApplication()
                .getResources().getDisplayMetrics().density);
    }

    private static View inflate(int layoutRes) {
        // 必须带上应用自己的 Material 主题：这些布局里的 MaterialCardView / MaterialToolbar
        // 都要从主题取属性，用裸的应用 Context 去 inflate 会直接抛 InflateException
        // （报的是「Error inflating class」，看不出真正原因是主题不对）。
        Context themed = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.Theme_AudioStream);
        View root = LayoutInflater.from(themed).inflate(layoutRes, null, false);
        layout(root);
        return root;
    }

    /** 真的走一遍测量与摆放，getLeft() 之类才有意义。 */
    private static void layout(View root) {
        int width = screenWidth();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        root.layout(0, 0, width, root.getMeasuredHeight());
    }

    /**
     * 这个控件里的文字离屏幕左边缘多少像素。
     *
     * 算法是「自身的 left 加上所有祖先的 left 与 padding」——这样得到的是屏幕坐标，
     * 而不是相对某个中间容器的位置；只比相对位置的话，两层嵌套各自缩进一点就会被漏掉。
     */
    private static int textLeftOnScreen(View view) {
        int x = view.getLeft() + view.getPaddingLeft();
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View container = (View) parent;
            x += container.getLeft() + container.getPaddingLeft();
            parent = container.getParent();
        }
        return x;
    }

    private static void collectTextViews(View view, List<TextView> out, boolean inHorizontalRow) {
        boolean hasText = view instanceof TextView
                && ((TextView) view).getText() != null
                && ((TextView) view).getText().length() > 0;
        boolean skip = view instanceof android.widget.EditText
                || view instanceof android.widget.Button
                || inHorizontalRow;
        if (hasText && !skip) out.add((TextView) view);

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            boolean horizontal = group instanceof LinearLayout
                    && ((LinearLayout) group).getOrientation() == LinearLayout.HORIZONTAL;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTextViews(group.getChildAt(i), out, inHorizontalRow || horizontal);
            }
        }
    }

    private static String nameOf(TextView view) {
        try {
            if (view.getId() == View.NO_ID) return view.getClass().getSimpleName();
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Exception e) {
            // 别的包的资源 id（例如 android.R.id.*）查不出名字，退回 id 数字
            return "id=" + view.getId();
        }
    }

    private static String brief(TextView view) {
        String text = view.getText().toString().replace('\n', ' ');
        return text.length() > 14 ? text.substring(0, 14) + "…" : text;
    }

    private static void assertAligned(View root, String what) {
        List<TextView> texts = new ArrayList<>();
        collectTextViews(root, texts, false);
        assertTrue(what + "：布局里参与对齐的正文控件不足两个，这个断言等于没跑（找到 "
                + texts.size() + " 个）", texts.size() >= 2);

        int expected = textLeftOnScreen(texts.get(0));
        StringBuilder report = new StringBuilder();
        boolean ok = true;
        for (TextView view : texts) {
            int left = textLeftOnScreen(view);
            report.append(String.format("%n  %-22s left=%dpx  \"%s\"", nameOf(view), left, brief(view)));
            if (Math.abs(left - expected) > TOLERANCE_PX) ok = false;
        }
        assertTrue(what + "：文字左边缘不在同一条线上（期望都等于 " + expected + "px）" + report, ok);
    }

    @Test
    public void 播放页所有文字左边缘对齐() {
        assertAligned(inflate(R.layout.fragment_playback), "播放页");
    }

    @Test
    public void 共享页所有文字左边缘对齐() {
        View root = inflate(R.layout.fragment_share);
        // 这两行是运行时才填的，inflate 出来是空的，不填就等于没测
        ((TextView) root.findViewById(R.id.shareDeviceText)).setText("用电脑拉这台手机的麦克风");
        layout(root);
        assertAligned(root, "共享页");
    }

    @Test
    public void 设置页所有文字左边缘对齐() {
        View root = inflate(R.layout.fragment_settings);
        ((TextView) root.findViewById(R.id.identityText)).setText("名称：Android Phone");
        layout(root);
        assertAligned(root, "设置页");
    }

    // 日志页没有单独的「页内对齐」用例：这一页只有一块日志，只有一个正文控件，
    // 自己跟自己比没有意义。它由下面那条跨页用例守着。

    /**
     * 全应用所有页面的正文文字必须在同一条线上，不只是「同一页里一致」。
     *
     * 这一条是补上前面那个盲区的：单看每一页时，播放页的「正在播放」在 96px、共享页卡片里的文字
     * 在 128px，各自页内都是整齐的，测试全绿——但用户翻页时会发现缩进不一样。
     * 这里把四个页面的正文左边缘放在一起比，跨页不一致才算失败。
     */
    @Test
    public void 四个页面的正文落在同一条线上() {
        List<int[]> measured = new ArrayList<>();
        List<String> names = new ArrayList<>();

        View playback = inflate(R.layout.fragment_playback);
        List<TextView> a = new ArrayList<>();
        collectTextViews(playback, a, false);
        measured.add(new int[]{textLeftOnScreen(a.get(0))});
        names.add("播放页");

        View share = inflate(R.layout.fragment_share);
        ((TextView) share.findViewById(R.id.shareDeviceText)).setText("用电脑拉这台手机的麦克风");
        layout(share);
        List<TextView> b = new ArrayList<>();
        collectTextViews(share, b, false);
        measured.add(new int[]{textLeftOnScreen(b.get(0))});
        names.add("共享页");

        View settings = inflate(R.layout.fragment_settings);
        ((TextView) settings.findViewById(R.id.identityText)).setText("名称：Android Phone");
        layout(settings);
        List<TextView> c = new ArrayList<>();
        collectTextViews(settings, c, false);
        measured.add(new int[]{textLeftOnScreen(c.get(0))});
        names.add("设置页");

        View log = inflate(R.layout.fragment_log);
        ((TextView) log.findViewById(R.id.logText)).setText("12:00:00 服务已连上");
        layout(log);
        List<TextView> d = new ArrayList<>();
        collectTextViews(log, d, false);
        measured.add(new int[]{textLeftOnScreen(d.get(0))});
        names.add("日志页");

        int first = measured.get(0)[0];
        StringBuilder report = new StringBuilder();
        boolean ok = true;
        for (int i = 0; i < measured.size(); i++) {
            report.append(String.format("%n  %-8s left=%dpx", names.get(i), measured.get(i)[0]));
            if (Math.abs(measured.get(i)[0] - first) > TOLERANCE_PX) ok = false;
        }
        assertTrue("四个页面的正文左边缘不在同一条线上（期望都等于 " + first + "px）" + report, ok);
    }

    /** 播放列表里的一路。它的文字也是运行时填的。 */
    @Test
    public void 每一路来源的卡片内文字左边缘对齐() {
        View card = inflate(R.layout.item_route);
        ((TextView) card.findViewById(R.id.routeTitle)).setText("192.168.1.10 · 扬声器");
        ((TextView) card.findViewById(R.id.routeState)).setText("正在播放");
        ((TextView) card.findViewById(R.id.routeVolumeText)).setText("100%");
        layout(card);
        assertAligned(card, "一路来源的卡片");
    }

    /**
     * 卡片里的文字与卡片外的文字必须在同一条线上。
     *
     * 这是用户反馈的核心：页面里既有一张张卡片，也有直接挂在页面上的小标题（「正在播放」），
     * 两者的文字左边缘如果不一致，看上去就是「每一行离屏幕边缘的宽度都不一样」。
     */
    @Test
    public void 卡片内与卡片外的文字落在同一条线上() {
        View root = inflate(R.layout.fragment_playback);
        List<TextView> texts = new ArrayList<>();
        collectTextViews(root, texts, false);
        int left = textLeftOnScreen(texts.get(0));
        // 卡片外的「正在播放」在 routesTitle；卡片内的正文以 hostInput 的描边为准量不方便，
        // 所以改量卡片里的标题类控件：routesEmptyText 挂在卡片外，用它做对照。
        TextView outside = root.findViewById(R.id.routesTitle);
        assertTrue("页面上的小标题「正在播放」与其它正文不在同一条线上（" + textLeftOnScreen(outside)
                + "px vs " + left + "px）", Math.abs(textLeftOnScreen(outside) - left) <= TOLERANCE_PX);
    }

    /** 四个页签是等宽的文字，不该有图标位留下的空白。 */
    @Test
    public void 底部页签是等宽文字且没有图标() {
        View root = inflate(R.layout.activity_main);
        ViewGroup group = root.findViewById(R.id.bottomNav);
        assertTrue("页签数量不对：找到 " + group.getChildCount() + " 个", group.getChildCount() == 4);

        List<Integer> widths = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View tab = group.getChildAt(i);
            assertTrue("页签 " + i + " 不是 TextView，说明里面混进了图标", tab instanceof TextView);
            TextView text = (TextView) tab;
            for (android.graphics.drawable.Drawable d : text.getCompoundDrawables()) {
                assertTrue("页签 " + i + " 用了 drawable，应该是纯文字", d == null);
            }
            widths.add(tab.getWidth());
        }
        for (int w : widths) {
            assertTrue("四个页签不等宽：" + widths, Math.abs(w - widths.get(0)) <= TOLERANCE_PX);
        }
    }
}
