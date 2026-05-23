# Android CLI 与测试环境 Runbook

本文档记录 UniVCP 在 Windows 上常用的 Android CLI 配置方式，方便做构建、连接设备、跑测试和排障。命令默认在 PowerShell 中执行。

## 这套工具能做什么

- 管理 Android SDK 包
- 检查 `adb`、`emulator`、`sdkmanager` 是否可用
- 辅助真机/模拟器调试
- 让测试环境更容易脚本化

它不是构建项目的核心，但很适合配合 Gradle、ADB 和 Android Studio 一起用。

## 目前约定

当前本机 SDK 位置：

```text
C:\Users\CHENXI\AppData\Local\Android\Sdk
```

当前 Android CLI 位置：

```text
C:\Users\CHENXI\AppData\AndroidCLI
```

已经配置到用户环境变量的内容：

- `ANDROID_HOME`
- `ANDROID_SDK_ROOT`
- `Path` 中的
  - `C:\Users\CHENXI\AppData\AndroidCLI`
  - `C:\Users\CHENXI\AppData\Local\Android\Sdk\platform-tools`
  - `C:\Users\CHENXI\AppData\Local\Android\Sdk\emulator`
  - `C:\Users\CHENXI\AppData\Local\Android\Sdk\cmdline-tools\latest\bin`

## 安装与更新

官方安装器：

```powershell
curl.exe -fL https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd -o $env:TEMP\android-cli-install.cmd
& $env:TEMP\android-cli-install.cmd
```

说明：

- 不要在 PowerShell 里写 `"%TEMP%"`，那是 `cmd.exe` 的变量风格。
- 在 PowerShell 里要用 `$env:TEMP`。

更新 CLI：

```powershell
android update
```

如果官方回 `Already up-to-date`，说明已经是最新版本。

## 常用验证

打开新终端后先检查：

```powershell
android --version
android info
adb version
Get-Command android, adb
```

如果你要确认 SDK 根目录：

```powershell
echo $env:ANDROID_HOME
echo $env:ANDROID_SDK_ROOT
```

## 常用测试命令

构建 Debug：

```powershell
.\gradlew.bat assembleDebug
```

跑 JVM 单测：

```powershell
.\gradlew.bat test
```

跑仪器测试：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

跑 Lint：

```powershell
.\gradlew.bat lint
```

## 设备连接

查看已连接设备：

```powershell
adb devices
```

重启 adb：

```powershell
adb kill-server
adb start-server
```

## 常见坑

- `curl: (23) client returned ERROR on write`
  - 多半是 PowerShell 变量写法不对，优先检查 `"%TEMP%"` 这种写法。
- `sdkmanager` 报 Java 版本检查错误
  - 这台机器上旧 `sdkmanager` 对 Java 24 有兼容性问题。
  - 优先用新 `android sdk ...` 命令管理包。
- 新开终端后找不到 `android` 或 `adb`
  - 重新打开 PowerShell，让用户级 `Path` 生效。
- `adb` 找得到，但 `sdkmanager` 不正常
  - 先确认 `cmdline-tools\latest\bin` 是否在 `Path` 里。

## 对 UniVCP 最实用的建议

对这个仓库来说，最有价值的是把以下三件事稳定住：

1. `adb` 能连设备
2. `./gradlew.bat assembleDebug` 能过
3. `./gradlew.bat test` 能过

这样你就可以把 SDK、设备、构建、测试串成一条稳定链路，排障也更直接。
