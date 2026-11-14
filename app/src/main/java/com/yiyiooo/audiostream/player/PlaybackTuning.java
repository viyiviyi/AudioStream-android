package com.yiyiooo.audiostream.player;

/**
 * 播放侧的几个刻度，集中在一处。
 *
 * 前三个与 PC 端网络播放同口径（目标水位 35ms、容量 120ms、起播攒到目标的一半），
 * 这样两端的听感延迟是一个量级；声卡缓冲那一条是安卓特有的：{@code AudioTrack} 自己也要有
 * 一段缓冲才不会每写一次就欠载，而它比 PC 端的 WASAPI 缓冲要粗。
 */
public final class PlaybackTuning {

    private PlaybackTuning() {
    }

    /**
     * 抖动缓冲的目标水位（毫秒）。端到端延迟主要由它决定：采集 10 + 水位 35 + 声卡若干。
     * 小于这个水位不起播，避免一开声就是一段欠载。
     */
    public static final int TARGET_BUFFER_MS = 35;

    /** 环形缓冲的容量（毫秒）。它是能扛住多大抖动的上限，不是目标水位。 */
    public static final int CAPACITY_MS = 120;

    /** 起播门槛：攒到目标水位的这个比例就开始出声。 */
    public static final double START_GATE_RATIO = 0.5;

    /**
     * 声卡缓冲（毫秒）。取 60ms：手机上这个值直接是「按下去到出声」的一部分，
     * 但比它再小就容易在系统忙时欠载，听起来是周期性的爆音。
     */
    public static final int SINK_BUFFER_MS = 60;

    /** 缓冲不足时等数据的上限（毫秒）。有数据时等待会立即返回，所以这个值只决定「完全没数据」时多久看一次。 */
    public static final int SHORT_WAIT_MS = 20;

    /**
     * 每次往声卡写多少毫秒。与 {@link #SINK_BUFFER_MS} 同量级。
     *
     * 块的边界由声卡缓冲的阻塞写决定：写得太碎（比如 20 毫秒）只会让写线程醒得更勤，
     * 每秒 50 次变成约 17 次，而端到端延迟不变——延迟由缓冲水位决定，不由每块多大决定。
     */
    public static final int CHUNK_MS = 60;
}
