# 签名目录说明（sign/release）

本目录存放**发布签名**：`private.pem` + `certificate.pem`。

生成方式与 `sign/debug/README.md` 相同（openssl 自签名或从手机端安卓 jks 提取）。

## 注意事项

- release 包务必**每次使用同一证书**，否则可能影响上架；
- 涉及 interconnect 通信时，证书必须与手机端桥接 App 的安卓签名**同源一致**；
- 在线签名生成工具（浏览器内完成，不上传密码）入口见 Vela 文档 interconnect 页。

依据：Vela 文档「设备通信 interconnect 开发注意事项」（https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html）
