package com.yiyiooo.audiostream.player;

import android.media.AudioAttributes;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import com.yiyiooo.audiostream.protocol.AudioFormat;

/**
 * 真机上的输出工厂：按对端给的格式建一块 {@code AudioTrack}。
 *
 * 建不起来时**必须抛错**，不能悄悄退回一个「能出声但不是这个格式」的东西——
 * 那会让「听不到」和「听起来不对」变成同一个现象，而它们要用户做的事完全不同。
 *
 * 关于延迟：{@code AudioTrack} 默认按音乐播放走大缓冲通道，我们请求 35ms 它可能给 80ms。
 * 所以这里优先用低延迟性能模式建，建不起来再退回默认——退回去只是延迟变高，不能变成没声音。
 */
public final class AudioTrackSinkFactory implements AudioSinkFactory {

    private static final String TAG = "AudioStream";

    @Override
    public AudioSink create(AudioFormat format) throws UnsupportedAudioException {
        int encoding = AudioTrackConfig.encodingOf(format);
        int channelMask = AudioTrackConfig.channelMaskOf(format);
        String description = AudioTrackConfig.describe(format);

        int minBuffer;
        try {
            minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding);
        } catch (Exception e) {
            throw new UnsupportedAudioException("系统不接受这个输出组合（" + description + "）：" + e.getMessage());
        }
        if (!AudioTrackConfig.isMinBufferUsable(minBuffer)) {
            throw new UnsupportedAudioException("这台手机不支持这样的输出（" + description + "）。");
        }

        // 先试低延迟通道，而且**请求值不取系统的 min**。
        // 实测这台设备的 getMinBufferSize 给的是 80ms（保守值），而低延迟模式下
        // 实际允许更小；若取 max(min, wanted)，请求 35ms 会被抬成 80ms，日志里就会出现
        // 「请求 80ms，实际 80ms」——看了半天以为已经调小了，实际一点没动。
        // 建不起来时回退到「不小于 min」的老口径，保证退回默认模式时不会失败。
        int lowLatencyBytes = AudioTrackConfig.bufferBytesFor(format);
        int fallbackBytes = Math.max(minBuffer, lowLatencyBytes);
        AudioTrack track = null;
        boolean lowLatency = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            track = tryBuild(format, encoding, channelMask, lowLatencyBytes, true);
            lowLatency = track != null;
        }
        if (track == null) {
            track = tryBuild(format, encoding, channelMask, fallbackBytes, false);
        }
        if (track == null) {
            throw new UnsupportedAudioException("建音频输出失败（" + description + "）。");
        }
        // 真正递进去的是哪个值：日志与 AudioSink 报出去的都必须是它，
        // 否则界面/日志里显示的是一个我们从没请求过的数
        int requestedBytes = lowLatency ? lowLatencyBytes : fallbackBytes;
        logBuffer(format, track, requestedBytes, lowLatency);
        // 浮点输出必须走另一组 write 重载，这里把「是不是浮点」带下去（见 AudioTrackSink.write）
        return new AudioTrackSink(track, requestedBytes,
                encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT);
    }

    /** 建一块声卡；建不起来返回 null，由调用方决定是否降级重试。 */
    private AudioTrack tryBuild(AudioFormat format, int encoding, int channelMask,
                                int bufferBytes, boolean lowLatency) {
        try {
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(format.sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (lowLatency) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            AudioTrack track = builder.build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                Log.w(TAG, "声卡没初始化成功（性能模式=" + (lowLatency ? "低延迟" : "默认") + "）");
                return null;
            }
            return track;
        } catch (Exception e) {
            // 低延迟模式在部分设备/ROM 上会拒绝某些采样率组合，这是要降级的信号而不是失败
            Log.w(TAG, "建声卡失败（性能模式=" + (lowLatency ? "低延迟" : "默认") + "）：" + e);
            return null;
        }
    }

    /**
     * 把「请求多少毫秒 / 系统实际给了多少毫秒 / 走的哪种通道」记进日志。
     *
     * 这一条是为了让「延迟高」可判断：请求值与系统实际分配是两回事，不打出真实值，
     * 改完之后只能靠猜，也没法回答用户「到底是哪儿慢」。
     */
    private void logBuffer(AudioFormat format, AudioTrack track, int requestedBytes, boolean lowLatency) {
        int requestedMs = format.bytesPerSecond() > 0
                ? (int) (requestedBytes * 1000L / format.bytesPerSecond()) : 0;
        int frames = 0;
        try {
            frames = track.getBufferSizeInFrames();
        } catch (Exception ignored) {
            // 读不到就只报请求值，不影响出声
        }
        int actualMs = format.sampleRate > 0 ? (int) (frames * 1000L / format.sampleRate) : 0;
        Log.i(TAG, "声卡就绪：" + format.describe()
                + "，请求缓冲 " + requestedMs + "ms，实际 " + actualMs + "ms（" + frames + " 帧）"
                + "，通道=" + (lowLatency ? "低延迟" : "普通"));
    }
}
