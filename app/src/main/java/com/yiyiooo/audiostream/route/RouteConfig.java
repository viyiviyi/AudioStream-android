package com.yiyiooo.audiostream.route;

import com.yiyiooo.audiostream.player.AudioSink;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一路拉取的配置：连哪台电脑的哪块设备、放多大声、是不是被用户停着。
 *
 * 它与运行时的那一份（{@link PlaybackRoute}）刻意分开：这个只描述「用户配了什么」，
 * 可序列化、可落盘、可比较；运行时那份带着 socket、声卡与重连计时，既不能也不该被序列化。
 * 界面上显示的状态一律取自运行时那份，这一份只负责「下次打开还在」。
 *
 * **id 由 host 与设备号拼出来，不是随机生成**：同一台电脑的同一块设备天然只该有一路。
 * 随机 id 会让「重复添加同一块设备」变成两路一模一样的声音叠在一起，用户听到的只是声音变大了，
 * 完全看不出是加了两路；而拼出来的 id 让去重变成一次字符串比较。
 */
public final class RouteConfig {

    /** 音量下限：等于静音，但这一路仍然连着（用户想留个连着但不吵的）。 */
    public static final float MIN_VOLUME = 0f;

    /**
     * 音量上限，与播放层同源（{@link AudioSink#MAX_VOLUME}，200%）。
     * 不在这里另写一个数字：上限一旦在两处各写一遍，早晚会出现「这里允许 200%、那里夹到 100%」。
     */
    public static final float MAX_VOLUME = AudioSink.MAX_VOLUME;

    /** 没设过音量时的默认值（100%）。 */
    public static final float DEFAULT_VOLUME = AudioSink.DEFAULT_VOLUME;

    /** 一路的稳定标识，也是「改这一路音量、停这一路」时的目标。 */
    private final String id;
    private final String host;
    private final String deviceId;
    private final String deviceName;
    private final float volume;

    /**
     * 用户把这一路停住了。
     *
     * 它与「删除」是两件事，这个字段就是那条分界线：停住的那一路仍然留在列表里，
     * 可以再启动、可以编辑、可以删；只是此刻不连、不出声。删掉则是把它从列表里移除。
     */
    private final boolean paused;

    private RouteConfig(String id, String host, String deviceId, String deviceName, float volume) {
        this(id, host, deviceId, deviceName, volume, false);
    }

    private RouteConfig(String id, String host, String deviceId, String deviceName,
                        float volume, boolean paused) {
        this.id = id;
        this.host = host;
        this.deviceId = deviceId;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.volume = clampVolume(volume);
        this.paused = paused;
    }

    /**
     * 新加一路。
     *
     * @param host       对端地址（调用方保证已经过 AppConfig.normalizeHost）
     * @param deviceId   对端设备号
     * @param deviceName 对端设备名，只用于显示；缺失不是错误
     * @param volume     这一路的输出音量，越界会被夹回
     */
    public static RouteConfig create(String host, String deviceId, String deviceName, float volume) {
        return new RouteConfig(key(host, deviceId), host, deviceId, deviceName, volume);
    }

    /**
     * 一路的唯一键：地址 + 设备号。
     *
     * 用 {@code \u0000} 分隔而不是常见的 {@code |} 或 {@code :}：设备号与主机名里都可能出现那些字符
     * （Windows 的设备号里就有 {@code \} 与 {@code .}），而 NUL 两个字段里都不会有，
     * 于是「(a,b) 与 (c,d) 拼出来相同」这件事不可能发生。
     */
    public static String key(String host, String deviceId) {
        return (host == null ? "" : host.trim()) + "\u0000" + (deviceId == null ? "" : deviceId.trim());
    }

    public String id() {
        return id;
    }

    public String host() {
        return host;
    }

    public String deviceId() {
        return deviceId;
    }

    public String deviceName() {
        return deviceName;
    }

    public float volume() {
        return volume;
    }

    /** 这一路是不是被用户停着。停着的那一路仍然留在列表里，只是不再连。 */
    public boolean isPaused() {
        return paused;
    }

    /** 界面上那张卡片标题用的「从哪来」。设备名缺失时退回设备号，不让标题空着。 */
    public String sourceLabel() {
        return host + (deviceId.isEmpty() ? "" : " · " + (deviceName.isEmpty() ? deviceId : deviceName));
    }

    /** 改音量后的新配置。它是个值对象，不原地改——界面上的滑条与已保存的配置不会互相打架。 */
    public RouteConfig withVolume(float value) {
        return new RouteConfig(id, host, deviceId, deviceName, value, paused);
    }

    /**
     * 改这一路连的地址与设备后的新配置。
     *
     * id 会跟着变（它就是这两个字段算出来的），所以调用方要按**新** id 去替换旧的那一路，
     * 否则界面上会留着一张永远连不上的旧卡片。
     */
    public RouteConfig withSource(String newHost, String newDeviceId, String newDeviceName) {
        String trimmedHost = newHost == null ? "" : newHost.trim();
        String trimmedDevice = newDeviceId == null ? "" : newDeviceId.trim();
        return new RouteConfig(key(trimmedHost, trimmedDevice), trimmedHost, trimmedDevice,
                newDeviceName == null ? "" : newDeviceName, volume, paused);
    }

    /** 返回一个「停着」或「要跑」的新配置。它不动 volume：停过的路再开起来，音量还是老样子。 */
    public RouteConfig withPaused(boolean value) {
        return new RouteConfig(id, host, deviceId, deviceName, volume, value);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        // id 不存：它是 host 与 deviceId 算出来的，存下来只会在两者被改动时留下一个对不上的旧 id
        json.put("host", host);
        json.put("deviceId", deviceId);
        json.put("deviceName", deviceName);
        // 音量按 double 存：org.json 的 put(String,float) 会走 float 重载并可能被 JSONObject.NULL 的判定绕进去
        json.put("volume", (double) volume);
        json.put("paused", paused);
        return json;
    }

    /**
     * 从落盘的那份读回来。缺 host 或 deviceId 就返回 null——那种记录连不上任何东西，
     * 留着它只会在界面上多出一张永远连不上的卡片，用户还得自己删。
     */
    public static RouteConfig fromJson(JSONObject json) {
        if (json == null) return null;
        String host = json.optString("host", "");
        String deviceId = json.optString("deviceId", "");
        if (host.isEmpty() || deviceId.isEmpty()) return null;
        String deviceName = json.optString("deviceName", "");
        float volume = (float) json.optDouble("volume", DEFAULT_VOLUME);
        // 老记录里没有 paused 这个字段，缺省是「要跑的」——升级上来的用户上次正在用，不该被莫名停住
        boolean paused = json.optBoolean("paused", false);
        return new RouteConfig(key(host, deviceId), host, deviceId, deviceName, volume, paused);
    }

    /**
     * 把音量夹进合法范围。规则只有一份，在播放层（{@link AudioSink#clampVolume}）。
     *
     * 旧版本存下的音量都在 0..1 之间，新范围是 0..2，所以老配置读进来天然合法，不需要额外迁移。
     */
    public static float clampVolume(float value) {
        return AudioSink.clampVolume(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RouteConfig)) return false;
        RouteConfig that = (RouteConfig) other;
        return Float.compare(that.volume, volume) == 0
                && paused == that.paused
                && id.equals(that.id)
                && host.equals(that.host)
                && deviceId.equals(that.deviceId)
                && deviceName.equals(that.deviceName);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + host.hashCode();
        result = 31 * result + deviceId.hashCode();
        result = 31 * result + deviceName.hashCode();
        result = 31 * result + Float.floatToIntBits(volume);
        result = 31 * result + (paused ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "RouteConfig{" + sourceLabel() + ", volume=" + volume + (paused ? ", 已停止" : "") + "}";
    }
}
