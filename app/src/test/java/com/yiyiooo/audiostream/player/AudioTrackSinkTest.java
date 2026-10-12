package com.yiyiooo.audiostream.player;

import android.media.AudioFormat;
import android.media.AudioTrack;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 音量放大到 200% 这件事的核心验证。
 *
 * 重点全在**限幅**上：超出 16 位整数范围的样本直接转 short 会回绕，
 * 32000 放大两倍变成 64000 → 回绕成 -1536，听感不是「太响」而是刺耳的撕裂噪声。
 * 所以这里不只断言「夹住了」，还专门断言「没有回绕」——把那个具体的错误值写出来，
 * 免得将来有人觉得限幅是多余的一步而删掉它。
 */
@RunWith(RobolectricTestRunner.class)
public class AudioTrackSinkTest {

    private static AudioTrack newTrack(int encoding) {
        return new AudioTrack.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(48000)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    // ---------- 限幅 ----------

    @Test
    public void 正样本超范围被夹到最大值而不是回绕() {
        assertEquals(Short.MAX_VALUE, AudioTrackSink.clipShort(40000f));
        // 不回绕的证据：直接转 short 会得到 -25536，那才是「刺耳噪声」的来源
        assertEquals((short) -25536, (short) 40000f);
        assertNotEquals((short) 40000f, AudioTrackSink.clipShort(40000f));
    }

    @Test
    public void 负样本超范围被夹到最小值而不是回绕() {
        assertEquals(Short.MIN_VALUE, AudioTrackSink.clipShort(-40000f));
        assertEquals((short) 25536, (short) -40000f);
        assertNotEquals((short) -40000f, AudioTrackSink.clipShort(-40000f));
    }

    @Test
    public void 范围内的样本原样保留() {
        assertEquals((short) 1000, AudioTrackSink.clipShort(1000f));
        assertEquals((short) -1000, AudioTrackSink.clipShort(-1000f));
        assertEquals((short) 0, AudioTrackSink.clipShort(0f));
    }

    @Test
    public void 浮点样本被夹在正负一之间() {
        assertEquals(1f, AudioTrackSink.clipFloat(1.5f), 0.0001f);
        assertEquals(-1f, AudioTrackSink.clipFloat(-1.5f), 0.0001f);
        assertEquals(0.5f, AudioTrackSink.clipFloat(0.5f), 0.0001f);
    }

    @Test
    public void 两倍增益确实把样本放大了() {
        assertEquals((short) 2000, AudioTrackSink.clipShort(1000f * 2f));
        assertEquals(0.6f, AudioTrackSink.clipFloat(0.3f * 2f), 0.0001f);
    }

    // ---------- 系统音量与软件增益的分工 ----------

    @Test
    public void 一百以下只动系统音量不做样本增益() {
        AudioTrackSink sink = new AudioTrackSink(newTrack(AudioFormat.ENCODING_PCM_16BIT), 48000, false);
        sink.setVolume(0.5f);
        assertEquals(0.5f, sink.volume(), 0.0001f);
        assertEquals("100% 以下不该自己动样本", 1f, sink.softwareGain(), 0.0001f);
    }

    @Test
    public void 超过一百时系统音量封顶并启用样本增益() {
        AudioTrackSink sink = new AudioTrackSink(newTrack(AudioFormat.ENCODING_PCM_16BIT), 48000, false);
        sink.setVolume(2f);
        assertEquals(2f, sink.volume(), 0.0001f);
        assertEquals("超过 100% 的部分由播放层自己乘上去", 2f, sink.softwareGain(), 0.0001f);
    }

    @Test
    public void 从放大调回一百以下时软件增益要交还给系统() {
        AudioTrackSink sink = new AudioTrackSink(newTrack(AudioFormat.ENCODING_PCM_16BIT), 48000, false);
        sink.setVolume(1.8f);
        assertEquals(1.8f, sink.softwareGain(), 0.0001f);
        sink.setVolume(0.4f);
        assertEquals("回到 100% 以下后不该再乘增益，否则会双重衰减", 1f, sink.softwareGain(), 0.0001f);
        assertEquals(0.4f, sink.volume(), 0.0001f);
    }

    // ---------- 夹取范围 ----------

    @Test
    public void 音量夹取范围是零到两百百分比() {
        assertEquals(0f, AudioSink.clampVolume(-1f), 0.0001f);
        assertEquals(2f, AudioSink.clampVolume(5f), 0.0001f);
        assertEquals(1.8f, AudioSink.clampVolume(1.8f), 0.0001f);
        assertEquals("默认是 100%，不是 0 也不是上限", 1f, AudioSink.DEFAULT_VOLUME, 0.0001f);
    }

    @Test
    public void 写坏的值退回默认而不是静音() {
        assertEquals(1f, AudioSink.clampVolume(Float.NaN), 0.0001f);
        assertTrue(AudioSink.clampVolume(1.5f) > 1f);
    }
}
