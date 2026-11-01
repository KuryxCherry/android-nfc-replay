# NFC 记录 · 重放

一个极简的 Android 小工具：**扫一次 NFC 卡，把唤起应用用的数据存下来，以后点一下列表条目就能重放**，不需要再贴卡，也不需要模拟 NFC。

## 直接下载

已经构建好的 APK：**[NFC-Replay-1.0.apk](./NFC-Replay-1.0.apk)**（21 KB，v1/v2/v3 签名，minSdk 21 / targetSdk 34，只申请 NFC 权限，无联网、无广告）

安装时需允许「安装未知应用」。

## 功能

- 读卡：读取 NDEF（URI / AAR 包名 / MIME / TEXT），保存到本地
- 重放：优先发 `ACTION_NDEF_DISCOVERED` + 重建的 `NdefMessage`；不行再退化成 `ACTION_VIEW`；最后兜底直接启动应用
- 长按条目：重放 / 复制数据 / 删除

## 怎么得到 APK

**方式 A：不用装任何环境（推荐）**

1. 在 GitHub 新建一个仓库，把这个目录里的所有文件推上去
2. 仓库 → Actions → “Build APK” → Run workflow
3. 编译完成后在该次运行页面底部下载 `nfc-replay-apk` 压缩包，里面就是 `app-debug.apk`

**方式 B：Android Studio**

用 Android Studio 打开本目录，等 Gradle 同步完，点 Run（需要 Gradle 8.7 / AGP 8.5.2 / JDK 17）。

## 已知限制

- **靠 UID 认卡的应用无效**：UID 在硬件层面无法伪造。如果原卡里根本没有 NDEF 数据，本工具也帮不上忙。
- `NfcAdapter.EXTRA_TAG` 是系统对象，普通应用无法伪造，所以**额外读取 `EXTRA_TAG.getId()` 的应用**拿不到原 UID。
- 大多数“贴卡自动打开 App”的场景只依赖 URI 或 AAR 包名，这些都能正常重放。

## 不用 Gradle 的构建方式

`build.sh` 是一条已验证可用的构建链（arm64 Linux 上实测出包）：`aapt2` 编译资源 → `javac` → `d8` 出 dex → `zipalign` → `apksigner` 签名。

```bash
apt install -y openjdk-17-jdk-headless aapt zipalign unzip
./build.sh      # 需要 SDK=/path 指向含 android-34/android.jar 与 build-tools 的目录
```

## 代码结构

- `MainActivity.java` 全部逻辑（约 320 行）
- 无第三方依赖，无广告，无联网权限
