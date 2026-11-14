package com.yiyiooo.audiostream.player;

/**
 * 播放侧的环形字节缓冲。
 *
 * 它只管「存字节、取字节」，不管什么时候该出声、欠载了怎么办——那些是
 * {@link PlaybackSession} 的事。这样拆开是因为环本身能纯粹地测（溢出、对齐、容量），
 * 而水位判定要与声卡节拍一起测。
 *
 * 两条口径：
 * <ul>
 *   <li><b>按帧对齐</b>：读写都以整数个采样帧为单位。半帧写进声卡，后面所有采样都会左右错位，
 *       听感是持续的杂音。</li>
 *   <li><b>写满丢最旧的</b>：网络一阵猛冲时宁可丢掉最老的一段，也不能整批丢新到的——
 *       丢新到的那批等于把整个缓冲连内容一起清空，听起来就是一段突兀的静音。</li>
 * </ul>
 *
 * <b>为什么它自己带锁与等待</b>：写线程在拿不到数据时必须能睡着等，而不是每隔几毫秒醒一次
 * 看看有没有数据。早先这个环是纯数据结构、等待只能靠调用方 sleep 轮询，结果是「连上了对端、
 * 对端却没在出声」时写线程每秒空醒两百次——手机放着不动也掉电。现在改成写入时唤醒等待者，
 * 没数据时最多等调用方给的上限。真正的等待由这里承担，调用方只管「等不到就按自己的策略处理」。
 *
 * 单读单写也照样加锁：等待本身就需要条件变量，而且「有数据没有」这个判断必须与写入
 * 在同一把锁上，否则会出现「检查时没数据、睡下去之后数据来了却没人叫」的漏醒。
 */
public final class RingBuffer {

    private final byte[] data;
    /** 一个采样帧的字节数（声道数 × 位深 / 8）。读写都按它的整数倍进行。 */
    private final int frameSize;

    /** 读写的互斥与等待都在这上面。 */
    private final Object lock = new Object();

    private int readIndex;
    private int size;

    private long droppedBytes;
    private long writtenBytes;
    private long readBytes;

    /**
     * 进过等待（或被超时放出来）的次数。
     *
     * 这是「忙等了多少次」的直接量化：它涨得越快，说明写线程醒得越频繁。留着它是因为
     * 耗电问题没有别的可观测量——CPU 占用在音频播放里本来就看不出差别，而唤醒次数能看出。
     */
    private long awaitCalls;

    /** 关掉之后等待者立刻返回，免得写线程卡在 wait 里出不来。 */
    private boolean closed;

    public RingBuffer(int capacityBytes, int frameSize) {
        if (frameSize <= 0) throw new IllegalArgumentException("frameSize 必须为正：" + frameSize);
        if (capacityBytes < frameSize) capacityBytes = frameSize;
        // 容量取整到帧的整数倍，免得尾部永远填不满、看起来像一直欠载
        this.data = new byte[(capacityBytes / frameSize) * frameSize];
        this.frameSize = frameSize;
    }

    public int frameSize() {
        return frameSize;
    }

    public int capacity() {
        return data.length;
    }

    /** 已经攒在缓冲里的字节数。 */
    public int available() {
        synchronized (lock) {
            return size;
        }
    }

    public int free() {
        synchronized (lock) {
            return data.length - size;
        }
    }

    /** 缓冲里有多少毫秒的音频。 */
    public int availableMs(int bytesPerSecond) {
        if (bytesPerSecond <= 0) return 0;
        synchronized (lock) {
            return (int) (size * 1000L / bytesPerSecond);
        }
    }

    /** 溢出丢掉的字节数。 */
    public long droppedBytes() {
        synchronized (lock) {
            return droppedBytes;
        }
    }

    public long writtenBytes() {
        synchronized (lock) {
            return writtenBytes;
        }
    }

    public long readBytes() {
        synchronized (lock) {
            return readBytes;
        }
    }

    /** 进过等待的次数。见 {@link #awaitCalls} 的说明。 */
    public long awaitCalls() {
        synchronized (lock) {
            return awaitCalls;
        }
    }

    public void clear() {
        synchronized (lock) {
            readIndex = 0;
            size = 0;
        }
    }

    /**
     * 等到有数据、或等到超时、或等到这个环被关掉。
     *
     * @return 真的等到数据了为 true；超时或被关掉为 false。
     * @param timeoutMs 最多等多久（毫秒）。有数据时立即返回，所以这个值只决定「完全没数据时
     *                  多久看一次」，不影响正常播放的响应速度。
     */
    public boolean awaitData(long timeoutMs) {
        synchronized (lock) {
            awaitCalls++;
            if (size > 0 || closed) return size > 0;
            long budget = Math.max(1L, timeoutMs);
            long deadline = System.currentTimeMillis() + budget;
            long left = budget;
            // 循环而不是单次 wait：wait 可能被虚假唤醒，也可能被别的写入先叫醒又一次取空
            while (size == 0 && !closed && left > 0) {
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                left = deadline - System.currentTimeMillis();
            }
            return size > 0;
        }
    }

    /**
     * 唤醒所有等待者，并让之后的 {@link #awaitData} 立刻返回 false。
     * 关掉写线程时必须调它，否则写线程会卡在 wait 里几十毫秒才醒来。
     */
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    /**
     * 写入一段音频。装不下时丢**最旧的**那些字节（丢的粒度也是整帧），
     * 并把丢掉的字节数记下来：界面上「丢了多少」比「有没有丢过」有用得多。
     */
    public void write(byte[] src, int offset, int length) {
        if (src == null || length <= 0) return;
        if (offset < 0 || offset + length > src.length) {
            throw new IndexOutOfBoundsException("越界写入：offset=" + offset + " length=" + length);
        }
        synchronized (lock) {
            int remaining = length;
            int from = offset;

            if (remaining > data.length) {
                // 一次塞进来比整个环还多：只能留住最后能装下的那一段
                int skip = remaining - (data.length / frameSize) * frameSize;
                droppedBytes += skip;
                from += skip;
                remaining -= skip;
            }

            if (remaining > data.length - size) {
                int need = remaining - (data.length - size);
                // 丢最旧的要整帧丢
                int toDrop = ((need + frameSize - 1) / frameSize) * frameSize;
                if (toDrop > size) toDrop = (size / frameSize) * frameSize;
                discardOldest(toDrop);
                droppedBytes += toDrop;
            }

            int writeIndex = (readIndex + size) % data.length;
            int first = Math.min(remaining, data.length - writeIndex);
            System.arraycopy(src, from, data, writeIndex, first);
            if (remaining > first) {
                System.arraycopy(src, from + first, data, 0, remaining - first);
            }
            size += remaining;
            writtenBytes += length;
            // 写入即唤醒：等数据的写线程要立刻醒，而不是等下一个轮询周期
            lock.notifyAll();
        }
    }

    /**
     * 取一段音频，最多 {@code maxLength} 字节，且只取整帧。
     * 返回实际取到的字节数；不足一帧时返回 0（这时候不该往声卡写任何东西）。
     */
    public int read(byte[] dst, int offset, int maxLength) {
        synchronized (lock) {
            int aligned = (Math.min(maxLength, size) / frameSize) * frameSize;
            if (aligned <= 0) return 0;
            int first = Math.min(aligned, data.length - readIndex);
            System.arraycopy(data, readIndex, dst, offset, first);
            if (aligned > first) {
                System.arraycopy(data, 0, dst, offset + first, aligned - first);
            }
            readIndex = (readIndex + aligned) % data.length;
            size -= aligned;
            readBytes += aligned;
            return aligned;
        }
    }

    private void discardOldest(int bytes) {
        if (bytes <= 0) return;
        readIndex = (readIndex + bytes) % data.length;
        size -= bytes;
    }
}
