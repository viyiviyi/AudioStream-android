package com.yiyiooo.audiostream.player;

/**
 * 「把 PCM 送到声卡」这唯一一件事的接口。
 *
 * 抽出这一层不是为了将来换实现，而是为了能测：{@link PlaybackSession} 里那些判断
 * （水位、起播门槛、欠载补帧、溢出丢帧）不该只能在真机上调，它们在 JVM 上用假 sink 就能验。
 */
public interface AudioSink {

    /**
     * 音量上限：200%。
     *
     * 100% 是原始增益（0 分贝）。超过它的部分只能靠在样本上乘增益来放大，因为
     * {@code AudioTrack.setVolume} 的合法上限就是 1；也就是说「放大」这件事是播放层自己做的，
     * 不是把系统音量调过头。
     *
     * 为什么封顶 200%：数字域里的放大不产生信息，越过 0 分贝的部分全靠削掉波峰换来。
     * 200% 时大约只有最响的段落会被削到，再往上整段音乐都会开始发闷。
     */
    float MAX_VOLUME = 2f;

    /** 没设过音量时的默认值：100%，原始增益。 */
    float DEFAULT_VOLUME = 1f;

    /**
     * 写一段 PCM。阻塞直到写完或声卡不再接受（停止时）。
     *
     * @return 实际写进去的字节数；小于 length 说明这条输出已经停了。
     */
    int write(byte[] data, int offset, int length);

    /** 开始出声。 */
    void play();

    /** 停止并释放。可以重复调用。 */
    void release();

    /** 声卡自己的缓冲有多少字节。它决定了「我们这边缓一下」的上限。 */
    int bufferSizeBytes();

    /**
     * 设置这条输出的音量，取值 0..{@link #MAX_VOLUME}。
     *
     * 只影响本应用播出去的声音，**不动系统媒体音量**——用户要的是「这台手机放电脑的声音小一点」，
     * 而不是把整台手机的媒体音量压下去；后者会连他自己在放的音乐一起变轻，且退出应用也不会恢复。
     * 可以在任何时刻调（播放中、暂停中、还没 play 都行）。
     */
    void setVolume(float volume);

    /**
     * 把音量夹进合法范围。
     *
     * 放在接口上而不是各调用点各写一遍：界面的滑条、落盘前的校验、播放层真正取值这三处
     * 必须得出同一个结果，否则会出现「滑条显示 180%，实际放出来是 100%」这种对不上的情况。
     *
     * NaN 一律当默认值：它多半来自写坏的偏好，而夹成 0 会让用户以为程序坏了（静音了却没动过滑条）。
     */
    static float clampVolume(float value) {
        if (Float.isNaN(value)) return DEFAULT_VOLUME;
        if (value < 0f) return 0f;
        return value > MAX_VOLUME ? MAX_VOLUME : value;
    }
}
