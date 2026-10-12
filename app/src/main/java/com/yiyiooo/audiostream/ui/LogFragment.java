package com.yiyiooo.audiostream.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.yiyiooo.audiostream.AppLog;
import com.yiyiooo.audiostream.R;

/**
 * 日志页：把全应用共用的那条日志显示出来。
 *
 * 它自己不产生任何日志，只在显示期间挂上监听——这样不管在哪一页发生的事都看得到，
 * 而这页没打开时也没有人白白重绘。
 */
public class LogFragment extends Fragment {

    private TextView logText;
    private ScrollView logScroll;

    private final AppLog.Listener listener = this::refresh;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        logText = view.findViewById(R.id.logText);
        logScroll = view.findViewById(R.id.logScroll);
        view.findViewById(R.id.clearLogButton).setOnClickListener(v -> AppLog.get().clear());
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        AppLog.get().setListener(listener);
        refresh();
    }

    @Override
    public void onPause() {
        // 摘掉监听：留着它等于每次记日志都去重绘一个看不见的界面
        AppLog.get().setListener(null);
        super.onPause();
    }

    private void refresh() {
        if (logText == null) return;
        logText.setText(AppLog.get().text());
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }
}
