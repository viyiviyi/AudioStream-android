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
     * 抖动缓冲的目标水位（毫秒）。端到端延迟主要由它决定：采集 10 + 水位若干 + 声卡若干。
     * 小于这个水位不起播，避免一开声就是一段欠载。
     *
     * 35 → 25：局域网里 35ms 是富余的，而它每多 10ms 就是用户能直接感觉到的一段延迟。
     * 往下调的风险是网络抖动时更容易欠载，表现为极短的一下重复（不是空白，见 pump 里的补帧）；
     * 是否够用看欠载计数，不靠感觉。
     */
    public static final int TARGET_BUFFER_MS = 25;

    /** 环形缓冲的容量（毫秒）。它是能扛住多大抖动的上限，不是目标水位。 */
    public static final int CAPACITY_MS = 120;

    /** 起播门槛：攒到目标水位的这个比例就开始出声。 */
    public static final double START_GATE_RATIO = 0.5;

    /**
     * 声卡缓冲（毫秒）。这是延迟里最大的一项，比抖动缓冲影响还大。
     *
     * 60 → 35：60ms 那一版走的是音乐播放的大缓冲通道，实际分配往往比请求的还大。
     * 现在同时开了低延迟模式（见 AudioTrackSinkFactory），请求值才有意义。
     * 再往下压就要看欠载了——太小会变成周期性的爆音，比延迟更让人受不了。
     */
    public static final int SINK_BUFFER_MS = 35;

    /** 缓冲不足时等数据的上限（毫秒）。有数据时等待会立即返回，所以这个值只决定「完全没数据」时多久看一次。 */
    public static final int SHORT_WAIT_MS = 20;

    /**
     * 每次往声卡写多少毫秒。
     *
     * **必须明显小于 {@link #SINK_BUFFER_MS}，绝不能等于它。** 曾经为了少醒几次把它改成
     * 60 毫秒（与声卡缓冲一样大），结果真机上播十几秒后永久无声：写 60 毫秒数据进 60 毫秒缓冲时，
     * AudioTrack.write 会返回部分写入（实测 15424/23040），而当时把「部分写入」当成
     * 「声卡不再接受」直接退出了写线程。
     *
     * 唤醒次数也不是耗电的主因——每秒 50 次唤醒对手机很轻；真正费电的是「没有数据时按毫秒级轮询」，
     * 那一条由 {@link RingBuffer#awaitAtLeast} 承担，与块大小无关。
     *
     * 20 → 10：开低延迟通道后声卡实际缓冲会变小（可能只有十几毫秒），写块比它大就会频繁部分写入，
     * 而部分写入是要丢数据的（见 pump）。代价是每秒唤醒从 50 次变 100 次，这一项比延迟更值得让。
     */
    public static final int CHUNK_MS = 10;
}
