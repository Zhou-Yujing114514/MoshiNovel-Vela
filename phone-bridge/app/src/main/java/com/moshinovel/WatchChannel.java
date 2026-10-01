package com.moshinovel;

import android.util.Log;

/**
 * 手环↔安卓 BLE 通道（system.interconnect 的手机对端）适配层。
 *
 * ──────────────────────────────────────────────────────────────────────────
 * TODO(SDK 集成点) —— 这是本工程唯一需要按官方 SDK 填充的位置：
 *
 *   文档：《小米穿戴第三方 APP 能力开放接口文档》
 *   获取页：https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html
 *           （页面底部「参考附录 > 点击下载」处提供 PDF 与 interconnect 测试 demo）
 *
 *   集成后应做的两件事：
 *     1) 在 SDK「收到手环上行消息」的回调里，把消息体（JSON 字符串）原样
 *        交给 {@link #dispatchToService(String)}，即转入业务层 BridgeService；
 *     2) 在 {@link #sendToWatch(String)} 里调用 SDK 的「向手环发送数据」接口，
 *        把业务层组好的 JSON 帧（响应帧 / download_progress / download_chunk）发回去。
 *
 *   硬性前提（见 vela-capability.md i.2）：
 *     - 本 App applicationId = com.moshinovel，与手环 manifest package 一致；
 *     - 本 App 签名证书必须与 watch-app/sign/{debug,release} 下的 pem 同源。
 *
 *   当前沙箱内未获取到该 SDK 文档，故提供一个日志回退实现
 *   （LoggingWatchChannel），使 BridgeService 业务主循环可独立跑通/单测；
 *   接入真机 SDK 时替换为真实实现即可，业务代码零改动。
 * ──────────────────────────────────────────────────────────────────────────
 */
public abstract class WatchChannel {

    private static final String TAG = "WatchChannel";

    /** 业务层（BridgeService）注册的上行监听。 */
    protected WatchMessageListener listener;

    public interface WatchMessageListener {
        /** 手环侧发来的一条 JSON 报文（见 architecture.md §3.1）。 */
        void onWatchMessage(String json);
    }

    public void setWatchMessageListener(WatchMessageListener l) {
        this.listener = l;
    }

    /** SDK 回调里收到手环上行 JSON 后，调用此方法转交业务层。 */
    protected final void dispatchToService(String json) {
        Log.i(TAG, "watch -> phone: " + json);
        if (listener != null) {
            listener.onWatchMessage(json);
        }
    }

    /** 业务层下发 JSON 帧到手环（响应 / 推送帧）。 */
    public abstract void sendToWatch(String json);

    /** 建立通道（SDK 初始化、注册回调等）。 */
    public abstract void open();

    /** 关闭通道、释放 SDK 资源。 */
    public abstract void close();

    /**
     * 日志回退实现：未接入官方 SDK 时使用。
     * sendToWatch 仅打日志；不会真的发到手环——仅用于本地验证业务层 JSON 拼装。
     */
    public static class LoggingWatchChannel extends WatchChannel {
        @Override
        public void sendToWatch(String json) {
            Log.i(TAG, "phone -> watch (LOG-ONLY, SDK 未接入): " + json);
        }

        @Override
        public void open() {
            Log.w(TAG, "LoggingWatchChannel 已 open：当前为未接 SDK 的日志回退实现，"
                    + "真机联调请替换为官方 SDK 实现（见本类顶部 TODO）。");
        }

        @Override
        public void close() {
            Log.i(TAG, "LoggingWatchChannel closed");
        }
    }
}
