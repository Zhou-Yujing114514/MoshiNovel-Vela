package com.moshinovel;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

/**
 * 最小启动页：打开 App 即拉起前台桥接服务 BridgeService。
 * 无业务 UI —— 桥接对用户透明，真正的交互在手环端。
 */
public class MainActivity extends Activity {

    private static final int REQ_RECORD_AUDIO = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 语音输入（vt_asr）需要 RECORD_AUDIO 运行时授权：首次打开 App 即申请一次。
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},
                    REQ_RECORD_AUDIO);
        }

        Intent svc = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        TextView tv = new TextView(this);
        tv.setPadding(48, 64, 48, 48);
        tv.setText("摩柿小说 · 手机桥接\n\n"
                + "桥接服务已启动。\n"
                + "请在手机「小米运动健康」中保持与手环的连接，\n"
                + "然后在手环快应用里登录/取书即可。\n\n"
                + "本页面无需操作，可切后台。");
        setContentView(tv);
    }
}
