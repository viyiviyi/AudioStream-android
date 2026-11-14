package com.yiyiooo.audiostream.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 环形缓冲：只验它自己那点事——对齐、容量、丢最旧。 */
public class RingBufferTest {

    private static byte[] ramp(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = (byte) (i & 0xFF);
        return data;
    }

    @Test
    public void 写进去再读出来字节不变() {
        RingBuffer ring = new RingBuffer(64, 4);
        byte[] src = ramp(40);
        ring.write(src, 0, src.length);
        assertEquals(40, ring.available());
        byte[] dst = new byte[40];
        assertEquals(40, ring.read(dst, 0, 40));
        assertTrue(java.util.Arrays.equals(src, dst));
        assertEquals(0, ring.available());
        assertEquals(0, ring.droppedBytes());
    }

    @Test
    public void 不满一帧不读出来() {
        RingBuffer ring = new RingBuffer(64, 4);
        ring.write(ramp(6), 0, 6);
        byte[] dst = new byte[8];
        // 只有 1 个整帧（4 字节），剩下 2 字节留在环里等下一帧的后半截
        assertEquals(4, ring.read(dst, 0, 8));
        assertEquals(2, ring.available());
        assertEquals(0, ring.read(dst, 0, 8));
    }

    @Test
    public void 容量取整到帧() {
        assertEquals(60, new RingBuffer(63, 4).capacity());
        // 容量比一帧还小时，至少留一帧
        assertEquals(4, new RingBuffer(1, 4).capacity());
    }

    @Test
    public void 写满丢最旧的并记账() {
        RingBuffer ring = new RingBuffer(16, 4);
        ring.write(ramp(16), 0, 16);
        ring.write(new byte[]{9, 9, 9, 9}, 0, 4);
        assertEquals(16, ring.available());
        assertEquals(4, ring.droppedBytes());
        byte[] dst = new byte[16];
        assertEquals(16, ring.read(dst, 0, 16));
        // 最旧的那一帧（0,1,2,3）被丢了，现在从头是 4,5,6,7
        assertEquals(4, dst[0]);
        assertEquals(9, dst[12]);
    }

    @Test
    public void 一次塞进来比整个环还多的数据只留最后一段() {
        RingBuffer ring = new RingBuffer(16, 4);
        byte[] src = ramp(100);
        ring.write(src, 0, src.length);
        assertEquals(16, ring.available());
        assertEquals(84, ring.droppedBytes());
        byte[] dst = new byte[16];
        assertEquals(16, ring.read(dst, 0, 16));
        // 留下的是最后 16 个字节
        assertEquals(src[84], dst[0]);
        assertEquals(src[99], dst[15]);
    }

    @Test
    public void 环形回绕时内容仍连续() {
        RingBuffer ring = new RingBuffer(16, 4);
        byte[] dst = new byte[16];
        // 反复写读，让读写指针在环里绕好几圈
        for (int round = 0; round < 10; round++) {
            byte[] src = new byte[]{(byte) round, 1, 2, 3, (byte) round, 5, 6, 7};
            ring.write(src, 0, 8);
            assertEquals(8, ring.read(dst, 0, 8));
            assertTrue("第 " + round + " 轮内容错位", java.util.Arrays.equals(src, java.util.Arrays.copyOf(dst, 8)));
        }
        assertEquals(0, ring.droppedBytes());
    }

    @Test
    public void 水位按毫秒算() {
        RingBuffer ring = new RingBuffer(4800, 4);
        // 48000Hz 立体声 16 位 = 192000 字节/秒；4800 字节 = 25ms
        ring.write(ramp(4800), 0, 4800);
        assertEquals(25, ring.availableMs(192000));
        assertEquals(0, ring.availableMs(0));
    }

    @Test
    public void 清空之后不再有残留() {
        RingBuffer ring = new RingBuffer(32, 4);
        ring.write(ramp(32), 0, 32);
        ring.clear();
        assertEquals(0, ring.available());
        byte[] dst = new byte[8];
        assertEquals(0, ring.read(dst, 0, 8));
        // 清空后再写，读出来的必须是新内容
        ring.write(new byte[]{7, 7, 7, 7}, 0, 4);
        assertEquals(4, ring.read(dst, 0, 4));
        assertEquals(7, dst[0]);
    }

    // ---------- 等待能力：写线程不能靠轮询 ----------

    @Test
    public void 有数据时等待立即返回() {
        RingBuffer ring = new RingBuffer(64, 4);
        ring.write(ramp(4), 0, 4);
        long started = System.currentTimeMillis();
        assertTrue(ring.awaitData(1000));
        long cost = System.currentTimeMillis() - started;
        assertTrue("有数据时不该真的等：" + cost + "ms", cost < 200);
    }

    @Test
    public void 没有数据时等待会超时并返回false() {
        RingBuffer ring = new RingBuffer(64, 4);
        long started = System.currentTimeMillis();
        assertFalse("没数据却返回了有数据", ring.awaitData(60));
        long cost = System.currentTimeMillis() - started;
        assertTrue("没等够就返回了：" + cost + "ms", cost >= 40);
    }

    @Test
    public void 写入会唤醒等待中的线程() throws Exception {
        RingBuffer ring = new RingBuffer(64, 4);
        final boolean[] got = new boolean[1];
        Thread waiter = new Thread(() -> got[0] = ring.awaitData(2000));
        waiter.start();
        Thread.sleep(100);
        ring.write(ramp(4), 0, 4);
        waiter.join(1000);
        assertTrue("写入之后等待者应当被唤醒并拿到数据", got[0]);
        assertEquals(1, ring.awaitCalls());
    }

    @Test
    public void 关闭会立刻唤醒等待者() throws Exception {
        RingBuffer ring = new RingBuffer(64, 4);
        final boolean[] got = new boolean[]{true};
        Thread waiter = new Thread(() -> got[0] = ring.awaitData(5000));
        waiter.start();
        Thread.sleep(100);
        long started = System.currentTimeMillis();
        ring.close();
        waiter.join(3000);
        long cost = System.currentTimeMillis() - started;
        assertFalse("关掉之后不该再返回有数据", got[0]);
        assertTrue("关掉应当立刻唤醒，而不是等满超时：" + cost + "ms", cost < 1000);
    }
}
