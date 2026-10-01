# 签名目录说明（sign/debug）

本目录存放**调试签名**：`private.pem` + `certificate.pem`。

## 生成方式

### 方式一：自签名（无手机对端联调时可用）

```bash
openssl req -newkey rsa:2048 -nodes -keyout private.pem -x509 -days 3650 -out certificate.pem
```

### 方式二：与手机端桥接 App 共用同一证书（必须，用于 interconnect 联调）

手环 QuickApp 与手机端桥接 App **包名一致 + 签名一致**才能通信。从安卓 jks 提取：

```bash
keytool -importkeystore -srckeystore keystore.jks -destkeystore keystore.p12 -srcstoretype jks -deststoretype pkcs12
openssl pkcs12 -nodes -in keystore.p12 -out keystore.pem
```

从 `keystore.pem` 中分别复制：

- `-----BEGIN PRIVATE KEY----- ... -----END PRIVATE KEY-----` → `private.pem`
- `-----BEGIN CERTIFICATE----- ... -----END CERTIFICATE-----` → `certificate.pem`

也可用 Vela 文档提供的【在线签名生成工具】上传 p12 生成（浏览器内完成，不上传密码）。

## 放置要求

- 开发包调试：放本目录（`sign/debug`）
- 发布包：放 `../release/`（`sign/release`）
- 涉及 interconnect 通信时，**debug 与 release 都应使用与手机 App 相同的证书**

依据：Vela 文档「设备通信 interconnect 开发注意事项」（https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html）
