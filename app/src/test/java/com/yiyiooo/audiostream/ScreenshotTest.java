package com.yiyiooo.audiostream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 把界面真渲染成 PNG，供人（其实主要是给我自己）看一眼。
 *
 * 为什么需要这个：这台开发机的图形栈会成片卡死——screencap 超时、System UI 自己弹 ANR，
 * 真机截图根本拿不到；而界面上的毛病（卡片看不出边界、各行左边缘不齐）**只有看见才知道**。
 * 前面两轮用户反馈的界面问题，我都是靠读布局代码推理，结果都没能提前发现。
 * Robolectric 的 NATIVE 图形模式能在 JVM 上走真实的 Canvas 渲染，把布局画成位图。
 *
 * 它验证的是「长什么样」，不是「对不对」——断言式的检查在 MainActivityLayoutTest 与
 * LayoutAlignmentTest 里。这里只负责出图，图放在 app/build/screenshots/。
 *
 * 注意：NATIVE 模式首次运行会去拉 Robolectric 的原生图形运行库（已配阿里云镜像），
 * 比普通单元测试慢；它不参与日常回归的断言，出图只是为了人工过目。
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33)
public class ScreenshotTest {

    /** 按实测最窄的常见手机宽度出图：窄屏上最容易看出边距与对齐的问题。 */
    private static final int WIDTH_PX = 1080;

    @Test
    @Config(qualifiers = "notnight")
    public void 渲染浅色主题的各页() throws Exception {
        renderAll("light");
    }

    @Test
    @Config(qualifiers = "night")
    public void 渲染深色主题的各页() throws Exception {
        renderAll("dark");
    }

    private void renderAll(String theme) throws Exception {
        render(R.layout.fragment_playback, theme, "playback", null);
        render(R.layout.item_route, theme, "route-card", this::fillRouteCard);
        render(R.layout.fragment_share, theme, "share", null);
        render(R.layout.fragment_settings, theme, "settings", null);
        render(R.layout.fragment_log, theme, "log", null);
        render(R.layout.activity_main, theme, "shell", null);
    }

    /**
     * 给「一路来源」的卡片填上真实内容再出图。
     * 不填的话出一张全是空占位框的图，看不出行距与对齐到底如何——那正是要检查的东西。
     */
    private void fillRouteCard(View root) {
        setText(root, R.id.routeTitle, "客厅电脑 · 扬声器");
        setText(root, R.id.routeState, "正在播放");
        setText(root, R.id.routeVolumeText, "135%");
    }

    private static void setText(View root, int id, String text) {
        TextView view = root.findViewById(id);
        if (view != null) view.setText(text);
    }

    private void render(int layoutId, String theme, String name, ViewFiller filler) throws Exception {
        Context base = RuntimeEnvironment.getApplication();
        // 必须套上本应用的主题：布局里用的都是 Material 的属性引用
        // （?attr/textAppearanceTitleMedium、?attr/colorSurfaceContainerHigh 等），
        // 用默认主题 inflate 会因为找不到这些属性而崩。
        Context themed = new ContextThemeWrapper(base, R.style.Theme_AudioStream);

        View root = LayoutInflater.from(themed).inflate(layoutId, null);
        if (filler != null) filler.fill(root);

        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.max(1, root.getMeasuredHeight());
        root.layout(0, 0, WIDTH_PX, height);

        Bitmap bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));

        File dir = new File("build/screenshots");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("建不了截图目录：" + dir.getAbsolutePath());
        }
        File out = new File(dir, theme + "-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        if (out.length() <= 0) {
            throw new IllegalStateException("截图是空的：" + out.getAbsolutePath());
        }
        System.out.println("截图: " + out.getAbsolutePath() + "  " + WIDTH_PX + "x" + height);
    }

    private interface ViewFiller {
        void fill(View root);
    }
}
