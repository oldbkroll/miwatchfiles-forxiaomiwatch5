# WatchFiles 0.3.1 Release 交接

日期：2026-07-24

状态：PASS。正式 Release 已使用本机 `askey1` 签名 keystore 构建并完成 APK、版本和签名校验；keystore 未提交到 GitHub。

## 构建配置

- Release 包名：`com.example.watchfiles`
- versionName：`0.3.1`
- versionCode：`6`
- targetSdk：`29`
- ABI：`armeabi-v7a`
- keystore：本机 `askey1`，格式 `PKCS12`
- key alias：`key0`
- 证书主体：`C=86, ST=hebei, L=langfang, OU=zhongshanspace, CN=oldbkroll`
- 证书 SHA-256：`86:3A:13:57:E2:22:FE:BE:2F:53:AB:27:EC:60:13:8D:47:E2:54:D1:A6:F5:F5:B4:6E:B0:6A:2D:A2:0F:45:9F`

密码没有写入项目文件、Gradle 配置或 Git 历史；构建时通过命令行 Gradle 属性注入。

## 最终产物

- APK：`app/build/outputs/apk/release/app-release.apk`
- APK 大小：`2,069,824 bytes`
- APK SHA-256：`8601828C247A614891D0EDCC3A15C70ED1E306AE09F5263911473BA07EF2AE27`
- APK 构建时间：`2026-07-24 17:01:38 +08:00`
- 构建命令：`.\gradlew.bat :app:assembleRelease --no-daemon --console=plain`，配合本机签名属性
- APK 签名：`apksigner verify --verbose --print-certs` PASS
- 签名方案：APK Signature Scheme v2=true；v1/v3/v3.1/v4=false；1 个 signer
- 签名者证书 SHA-256：`863a1357e222febe2f53ab27ec60138d47e254d1a6f5f5b46eb06a2da20f459f`

最终版本名改为 `0.3.1` 后必须以重新构建的 APK 为最终交付物；旧的 `0.3.1-dev` Release 产物不作为交付包。

## 发布边界

- `askey1` 已加入 `.gitignore`，不会进入提交或 GitHub。
- 本次 GitHub 上传只包含源码和项目文档，不包含 APK、keystore、密码或构建目录。
- 正式 APK 的 GitHub 交接方式为 Release 产物说明；若需要把 APK 附加到 GitHub Release，需另行确认发布标签和 Release 名称。
