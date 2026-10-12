package com.yiyiooo.audiostream.player;

import android.media.AudioTrack;

/**
 * {@link AudioSink} 在真机上的实现：一块 {@code AudioTrack}，流模式，写多少就播多少。
 *
 * 这里刻意不做任何缓冲与判断——缓冲在 {@link RingBuffer}，水位判定在 {@link PlaybackSession}。
 * 一旦把这层做厚，同一个问题就会有两处地方可以解释，查起来先得弄清是谁干的。
 *
 * 这里只做两件事：浮点格式不能走 {@code write(byte[], …)}；音量超过 100% 时在样本上补增益。
 */
public final class AudioTrackSink implements AudioSink {

    /** 16 位有符号整数能表示的范围，限幅用。 */
    private static final float SHORT_MAX = 32767f;
    private static final float SHORT_MIN = -32768f;

    private final AudioTrack track;
    private final int bufferSizeBytes;

    /** 这条输出是不是浮点编码。它决定 {@link #write} 走哪个重载。 */
    private final boolean floatEncoding;

    private volatile boolean released;

    /** 用户设的音量，0..{@link AudioSink#MAX_VOLUME}。默认满增益（100%）。 */
    private volatile float volume = 1f;

    /**
     * 需要我们自己乘上去的增益，>= 1。
     *
     * 100% 及以下时它是 1（一个样本都不碰，全部交给声卡自己的音量）；
     * 超过 100% 的部分才由它承担，因为 {@code AudioTrack.setVolume} 的上限就是 1。
     */
    private volatile float softwareGain = 1f;

    /** 浮点输出时把字节搬进 float[] 用的缓冲：复用，不在每个音频块上分配。 */
    private float[] floatScratch = new float[0];

    /** 16 位输出需要软件增益时用的缓冲，同上。 */
    private short[] shortScratch = new short[0];

    AudioTrackSink(AudioTrack track, int bufferSizeBytes, boolean floatEncoding) {
        this.track = track;
        this.bufferSizeBytes = bufferSizeBytes;
        this.floatEncoding = floatEncoding;
    }

    @Override
    public int write(byte[] data, int offset, int length) {
        if (released || length <= 0) return 0;
        float gain = softwareGain;
        if (floatEncoding) {
            return writeFloats(data, offset, length, gain);
        }
        if (gain != 1f) {
            return writeShorts(data, offset, length, gain);
        }
        // WRITE_BLOCKING：写不进去就等，别丢数据——丢了就是一声爆音，而等只是让缓冲多攒一点
        return track.write(data, offset, length, AudioTrack.WRITE_BLOCKING);
    }

    /**
     * 16 位整数输出，需要软件增益时走这里。
     *
     * 有增益就必须逐样本过一遍，不能像 100% 以下那样把字节原样丢给声卡。
     * 用 {@code write(short[], …)} 而不是就地改 byte[]：后者要自己处理小端字节序，
     * 而 short[] 这个重载由系统按本机字节序处理，少一处能错的地方。
     */
    private int writeShorts(byte[] data, int offset, int length, float gain) {
        int samples = length / 2;
        if (samples <= 0) return 0;
        if (shortScratch.length < samples) shortScratch = new short[samples];
        for (int i = 0; i < samples; i++) {
            int at = offset + i * 2;
            // 线上是小端
            short sample = (short) ((data[at] & 0xFF) | (data[at + 1] << 8));
            shortScratch[i] = clipShort(sample * gain);
        }
        int written = track.write(shortScratch, 0, samples, AudioTrack.WRITE_BLOCKING);
        return written <= 0 ? written : written * 2;
    }

    /**
     * 浮点输出必须走 {@code write(float[], …)}。
     *
     * 对 {@code ENCODING_PCM_FLOAT} 调 {@code write(byte[], …)} 会直接返回
     * {@code ERROR_INVALID_OPERATION}(-3)，一个采样也写不进去；而现场看起来只是
     * 「连上了、格式也对，就是没声音」。PC 端默认扬声器环回正是 32 位浮点，
     * 也就是用户最可能选的那个来源。
     *
     * 那个重载返回的是**float 个数**，这里统一换算成字节数，与其它重载一个口径。
     */
    private int writeFloats(byte[] data, int offset, int length, float gain) {
        int floats = length / 4;
        if (floats <= 0) return 0;
        if (floatScratch.length < floats) floatScratch = new float[floats];
        for (int i = 0; i < floats; i++) {
            int at = offset + i * 4;
            // 线上就是小端 float，与 AudioTrack 要的同一个字节序
            int bits = (data[at] & 0xFF)
                    | ((data[at + 1] & 0xFF) << 8)
                    | ((data[at + 2] & 0xFF) << 16)
                    | ((data[at + 3] & 0xFF) << 24);
            floatScratch[i] = clipFloat(Float.intBitsToFloat(bits) * gain);
        }
        int written = track.write(floatScratch, 0, floats, AudioTrack.WRITE_BLOCKING);
        return written <= 0 ? written : written * 4;
    }

    /**
     * 把增益后的样本夹回 16 位整数的可表示范围。
     *
     * 这一步不能省。超出范围的 float 直接转 short 会**回绕**（32768 变成 -32768），
     * 听感上不是「太响」而是刺耳的撕裂噪声——比削波难听得多，而且用户只会以为程序坏了。
     * 夹住之后的表现是「响的地方变平」，那是所有放大都会有的代价，用户能理解。
     */
    static short clipShort(float value) {
        if (value > SHORT_MAX) return Short.MAX_VALUE;
        if (value < SHORT_MIN) return Short.MIN_VALUE;
        return (short) value;
    }

    /** 浮点样本的限幅，范围是 [-1, 1]，理由同上。 */
    static float clipFloat(float value) {
        if (value > 1f) return 1f;
        if (value < -1f) return -1f;
        return value;
    }

    @Override
    public void play() {
        if (released) return;
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            track.play();
        }
    }

    @Override
    public void release() {
        if (released) return;
        released = true;
        try {
            if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                track.stop();
            }
        } catch (IllegalStateException ignored) {
            // 已经自己停了，没什么可做的
        }
        try {
            track.flush();
        } catch (IllegalStateException ignored) {
        }
        track.release();
    }

    @Override
    public int bufferSizeBytes() {
        return bufferSizeBytes;
    }

    /**
     * 设置本应用输出的音量，0..{@link AudioSink#MAX_VOLUME}。
     *
     * 分两段：100% 及以下交给 {@code AudioTrack.setVolume}（系统层，不碰别人的音量，
     * 也不用我们自己动样本）；超过 100% 的那部分只能在样本上乘——那个 API 的上限就是 1。
     *
     * **先设软件增益、再设系统音量**，顺序不能反。从 50% 调到 200% 时，
     * 反过来的中间态是「系统音量已经升到 1.0、增益还是 1.0」，随后增益跳到 2.0——
     * 这两步之间没有尖峰；但若先设系统音量再设增益，从 200% 调回 50% 时
     * 中间会出现「系统 1.0 × 增益 2.0 = 200%」的一瞬，用户正在听的是一下爆响。
     */
    @Override
    public void setVolume(float value) {
        float clamped = AudioSink.clampVolume(value);
        float gain = clamped > 1f ? clamped : 1f;
        float system = clamped > 1f ? 1f : clamped;

        softwareGain = gain;
        volume = clamped;

        if (released) return;
        try {
            track.setVolume(system);
        } catch (IllegalStateException ignored) {
            // 声卡正在被释放：这条设置丢了也无所谓，调用方重建时会再设一次
        }
    }

    /** 当前音量（0..2），给测试与界面读取。 */
    float volume() {
        return volume;
    }

    /** 当前需要自己乘上去的增益（1..2），给测试读取。 */
    float softwareGain() {
        return softwareGain;
    }
}
