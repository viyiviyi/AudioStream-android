package com.yiyiooo.audiostream.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.yiyiooo.audiostream.AppConfig;
import com.yiyiooo.audiostream.MachineIdentity;
import com.yiyiooo.audiostream.R;

/**
 * 设置页：外观三选，外加一行本机身份。
 *
 * 本机身份（名字与编号）放在这里而不是藏起来：这台手机在电脑的列表里显示的就是这个名字，
 * 用户想改的时候得先知道去哪儿看。
 */
public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        MaterialButtonToggleGroup group = view.findViewById(R.id.themeGroup);

        TextView identity = view.findViewById(R.id.identityText);
        MachineIdentity machine = MachineIdentity.load(requireContext());
        identity.setText(machine.name() + "\n" + machine.id());

        // 先把选中状态摆好再挂监听：check() 自己会触发一次回调，
        // 顺序反了会在界面刚起来时按当前设置重设一次主题（多余的一次重建）。
        group.check(buttonId(AppConfig.loadThemeMode(requireContext())));
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return; // 只看「被选中」那一次
            int mode = modeOf(checkedId);
            if (mode == AppConfig.loadThemeMode(requireContext())) return;
            AppConfig.saveThemeMode(requireContext(), mode);
            // setDefaultNightMode 会让 Activity 自己重建，界面不需要手工刷新
            AppCompatDelegate.setDefaultNightMode(delegateMode(mode));
        });
    }

    private static int buttonId(int mode) {
        if (mode == AppConfig.THEME_LIGHT) return R.id.themeLight;
        if (mode == AppConfig.THEME_DARK) return R.id.themeDark;
        return R.id.themeSystem;
    }

    private static int modeOf(int buttonId) {
        if (buttonId == R.id.themeLight) return AppConfig.THEME_LIGHT;
        if (buttonId == R.id.themeDark) return AppConfig.THEME_DARK;
        return AppConfig.THEME_SYSTEM;
    }

    private static int delegateMode(int mode) {
        if (mode == AppConfig.THEME_LIGHT) return AppCompatDelegate.MODE_NIGHT_NO;
        if (mode == AppConfig.THEME_DARK) return AppCompatDelegate.MODE_NIGHT_YES;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }
}
