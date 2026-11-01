#!/bin/bash
# 无需 Android Studio / Gradle 的构建脚本（arm64 Linux 已实测通过）
# 依赖：apt install -y openjdk-17-jdk-headless aapt zipalign unzip
set -e
SDK=${SDK:-/tmp/sdk}                    # 需含 android-34/android.jar 与 android-14/lib/{d8,apksigner}.jar
AJ=$SDK/android-34/android.jar
D8=$SDK/android-14/lib/d8.jar
AS=$SDK/android-14/lib/apksigner.jar

# 1. 生成带 package 属性的清单（Gradle 版清单不含 package，交给 namespace）
python3 - <<'PY'
s = open('app/src/main/AndroidManifest.xml').read()
s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
 '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
 '    package="com.nfcreplay"\n    android:versionCode="1"\n    android:versionName="1.0">')
open('/tmp/manifest_for_aapt.xml','w').write(s)
PY

rm -rf build; mkdir -p build/gen build/classes build/dex

# 2. 资源 + 清单 -> 基础 APK
aapt2 compile --dir app/src/main/res -o build/res.zip
aapt2 link -o build/base.apk -I $AJ --manifest /tmp/manifest_for_aapt.xml \
  --min-sdk-version 21 --target-sdk-version 34 --java build/gen build/res.zip

# 3. 编译 Java -> dex
javac -nowarn -source 8 -target 8 -bootclasspath $AJ -d build/classes \
  build/gen/com/nfcreplay/R.java app/src/main/java/com/nfcreplay/MainActivity.java
java -cp $D8 com.android.tools.r8.D8 --lib $AJ --min-api 21 --output build/dex \
  $(find build/classes -name '*.class')

# 4. 打包 + 对齐 + 签名
cp build/base.apk build/unaligned.apk
(cd build/dex && zip -q ../unaligned.apk classes.dex)
zipalign -f 4 build/unaligned.apk build/aligned.apk
[ -f /tmp/debug.keystore ] || keytool -genkeypair -keystore /tmp/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
java -cp $AS com.android.apksigner.ApkSignerTool sign \
  --ks /tmp/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out NFC-Replay-1.0.apk build/aligned.apk
echo "OK -> NFC-Replay-1.0.apk"
