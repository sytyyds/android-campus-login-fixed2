# 校园网自动登录 Android App

用 Android Studio 打开本目录，等待 Gradle 同步后运行或生成 APK。

首次使用：

1. 安装并打开 App。
2. 授予 Wi-Fi/附近设备权限。
3. 输入学号和密码，点击“连接并认证”。
4. 如果手机尚未连接 `zzuli-student`，按系统提示确认连接。

账号按中国联通格式提交：`,0,学号@unicom`。密码使用 Android Keystore 加密保存，不写入日志。

Android 10 及以上不允许普通 App 完全静默加入陌生 Wi-Fi，首次连接可能出现一次系统确认框；已经连接该 Wi-Fi 时，认证请求可以在后台完成。

工程未附带 Gradle wrapper；使用 Android Studio 的内置 Gradle 即可构建。需要联网下载 Android Gradle Plugin 和依赖。

不安装 Android Studio 的构建方式：

1. 解压本工程，把 `settings.gradle.kts`、`build.gradle.kts`、`app` 和 `.github` 直接放在 GitHub 仓库根目录。不要再套一层同名文件夹。
2. 打开仓库的 `Actions`，选择 `Build APK`，点击 `Run workflow`。
3. 构建成功后，在该运行记录底部的 `Artifacts` 下载 `campus-auto-login-debug`，解压即可得到 APK。

如果日志显示 `Plugin ... was not found`，先确认仓库根目录能直接看到这两个文件：`settings.gradle.kts` 和 `build.gradle.kts`。本工程已经在两个文件中固定了 Android Gradle Plugin `8.7.3` 和 Kotlin `2.0.21` 的版本。
