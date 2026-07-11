# mihomo-watch-proxy

为 **三星 Galaxy Watch7 国行版**（Wear OS 5, 32 位 armeabi-v7a, API 34）设计的 mihomo (Clash Meta) 代理 App。

> ⚠️ 国行三星手表禁用了 `VpnService`，传统 Clash/Shadowrocket 思路完全不适用。本项目通过 **Shizuku + 系统代理**绕过限制。

**包名**：`com.ys.proxy`

---

## 目录

- [为什么需要这个项目](#为什么需要这个项目)
- [工作原理](#工作原理)
- [功能特性](#功能特性)
- [项目结构](#项目结构)
- [构建方式](#构建方式)
- [使用说明](#使用说明)
- [代理兼容性](#代理兼容性)
- [已知限制](#已知限制)
- [⚠️ 注意事项（接手必读）](#️-注意事项接手必读)
- [接手指南](#接手指南)
- [常见问题排查](#常见问题排查)

---

## 为什么需要这个项目

| 方案 | 在国行 Galaxy Watch7 上的可行性 |
|------|--------------------------------|
| 传统 Clash (VpnService) | ❌ 三星国行系统禁用了 VpnService，无法建立 tun0 |
| `clone clashmeta + wear 声明` | ❌ 同样依赖 VpnService，禁用声明也救不了 |
| **Shizuku + 系统代理**（本项目） | ✅ 用 Shizuku 拿 shell 权限跑 mihomo，再 `settings put global http_proxy` 设全局代理 |

核心思路：**绕开 VpnService，改用系统级 HTTP 代理**。

---

## 工作原理

```
┌─────────────────────────────────────────────────┐
│  Wear OS App (本 App, 普通权限, 包名 com.ys.proxy)│
│    ↓ 通过 Shizuku binder                         │
│  Shizuku Service (shell 权限, uid=2000)          │
│    ↓ 执行 shell 命令                              │
│  mihomo 进程 (shell 启动, /data/local/tmp/)      │
│    ↓ 监听 127.0.0.1:7890 (mixed-port HTTP+SOCKS) │
│    ↓ 监听 127.0.0.1:7891 (socks-port 纯 SOCKS5)  │
│    ↓ 监听 127.0.0.1:9090 (external-controller)   │
│  系统全局代理: settings put global http_proxy    │
│    ↓                                              │
│  所有遵守系统代理的 App → 走 mihomo → 出网        │
└─────────────────────────────────────────────────┘
```

### 双通道设计（关键）

32 位 Wear OS 上 Shizuku 的 `bindUserService` 经常静默失败（详见 [ShizukuManager.kt](app/src/main/java/com/ys/proxy/ShizukuManager.kt) 注释）。本项目设计了双通道：

1. **主通道 - UserService**：通过 AIDL `IWatchService` 在 Shizuku 进程内执行命令
2. **备用通道 - 反射 `Shizuku.newProcess`**：反射调 Shizuku private static 方法，直接通过 binder 执行命令，**完全绕过 UserService**

`getRunner()` 优先 UserService，失败自动回退反射通道。**只要 Shizuku 运行中 + 已授权，反射通道一定可用**。

---

## 功能特性

- ✅ 多订阅管理：保存/载入/删除多个机场订阅
- ✅ 节点延迟测试（5 并发优化）+ 按延迟排序
- ✅ 持久化：订阅链接、排序偏好、开机自启
- ✅ 订阅热重载：切换订阅不停 mihomo 进程，不断流
- ✅ 通过当前代理下载新订阅（支持"用直连订阅 A 开代理，再切换到需代理的订阅 B"场景）
- ✅ 开机自启 mihomo
- ✅ 保存订阅时自定义名字
- ✅ 启动后 API 就绪轮询（解决启动后立即选节点失败）
- ✅ 诊断面板：Shizuku 状态、反射测试、bind 失败原因
- ✅ 支持所有 mihomo 协议（hy2/vless/vmess/trojan/ss/tuic/wireguard 等）
- ✅ **GeoIP/GeoSite 数据库内置**（解决国行手表直连 github 下载 mmdb 卡死问题）
- ✅ **独立 SOCKS5 端口 7891**（供 TgWrist 等需要纯 SOCKS 代理的 App 使用）
- ✅ **Jetpack Compose + Wear Material 3** UI
- ✅ **前台服务 + OngoingActivity**（表盘底部圆圈"运行中"指示器）
- ✅ **GitHub Actions Release 签名构建**（keystore 入仓库保证可覆盖安装）

---

## 项目结构

```
app/src/main/
├── java/com/ys/proxy/
│   ├── MainActivity.kt           # Wear OS Compose UI 主入口
│   ├── AppViewModel.kt           # 状态管理 + 业务编排（最核心）
│   ├── ShizukuManager.kt         # Shizuku 连接 + 反射通道
│   ├── MihomoController.kt       # mihomo 二进制/配置/geo数据/启动/停止
│   ├── MihomoApi.kt              # mihomo RESTful API 客户端
│   ├── MihomoForegroundService.kt # 前台服务 + 表盘指示器
│   ├── SubscriptionManager.kt    # 订阅下载 + 配置注入（含 geo 配置）
│   ├── SubscriptionCache.kt      # 订阅本地缓存
│   ├── SubscriptionStore.kt      # 多订阅本地存储 (SharedPreferences + JSON)
│   ├── ProxyIndicator.kt         # 诊断用代理指示器
│   └── WatchUserService.kt       # Shizuku UserService 实现 (AIDL)
├── aidl/com/ys/proxy/
│   └── IWatchService.aidl        # UserService 接口定义
├── assets/
│   ├── mihomo                    # mihomo armv7 二进制 (Actions 构建时下载)
│   ├── geoip.metadb              # GeoIP 数据库 (8.8MB, 随 APK 分发)
│   └── geosite.dat               # GeoSite 数据库 (4.2MB, 随 APK 分发)
├── res/xml/
│   └── network_security_config.xml  # 允许明文 HTTP 访问 127.0.0.1
└── AndroidManifest.xml           # 声明 watch feature + Shizuku provider
```

### 模块职责

| 文件 | 职责 |
|------|------|
| [AppViewModel.kt](app/src/main/java/com/ys/proxy/AppViewModel.kt) | 所有 UI state + 业务流程编排（启动/停止/切换订阅/测延迟）。**改业务逻辑主要改这里** |
| [ShizukuManager.kt](app/src/main/java/com/ys/proxy/ShizukuManager.kt) | Shizuku 状态检测、UserService 绑定、反射通道、诊断信息 |
| [MihomoController.kt](app/src/main/java/com/ys/proxy/MihomoController.kt) | mihomo 二进制释放、geo 数据安装、配置安装、进程启动/停止、全局代理设置 |
| [MihomoApi.kt](app/src/main/java/com/ys/proxy/MihomoApi.kt) | 调 mihomo 的 9090 API：查询节点、选节点、测延迟、热重载 |
| [SubscriptionManager.kt](app/src/main/java/com/ys/proxy/SubscriptionManager.kt) | 下载订阅（支持 viaProxy 走当前代理）+ 注入控制面板配置（含 geo 配置） |
| [SubscriptionStore.kt](app/src/main/java/com/ys/proxy/SubscriptionStore.kt) | 多订阅持久化（JSON 数组存 SharedPreferences） |
| [MihomoForegroundService.kt](app/src/main/java/com/ys/proxy/MihomoForegroundService.kt) | 前台服务 + OngoingActivity 表盘指示器 |

---

## 构建方式

### 推荐：GitHub Actions（无需本地环境）

push 到 `main` 分支自动触发 [.github/workflows/build.yml](.github/workflows/build.yml)，构建产物在 Actions → Artifacts 下载（保留 90 天）。

工作流自动完成：
1. 下载 MetaCubeX/mihomo 最新 android-armv7 二进制
2. 解压到 `app/src/main/assets/mihomo`
3. `./gradlew assembleRelease` + `assembleDebug` 构建 APK（release 用 debug 签名，可覆盖安装）
4. 上传 artifact（保留 90 天）

**版本号自动递增**：`versionCode` = GitHub Actions run number，`versionName` = `1.0.<run_number>`。

### 本地构建

**前置要求**：JDK 17 + Android SDK (platform 34 + build-tools 34.0.0)

```bash
# 1. 手动下载 mihomo armv7 二进制到 assets
curl -sL https://github.com/MetaCubeX/mihomo/releases/latest/download/mihomo-android-armv7-vX.Y.Z.gz -o mihomo.gz
gunzip mihomo.gz && chmod 755 mihomo
mkdir -p app/src/main/assets && cp mihomo app/src/main/assets/mihomo

# 2. 构建
./gradlew assembleDebug

# 3. APK 在
ls app/build/outputs/apk/debug/*.apk
```

### 关键构建配置

- `abiFilters += "armeabi-v7a"`：只打 32 位，适配国行手表
- `minSdk = 30`：Wear OS 3 起步
- `targetSdk = 34`：Wear OS 5
- `signingConfig = debug`：release 也用 debug 签名方便直接装（keystore 在 `app/debug.keystore`，提交仓库保证签名一致可覆盖安装）
- `androidResources.noCompress`：`mihomo` / `metadb` / `dat` / `mmdb` 不压缩，否则二进制 assets 损坏
- Kotlin 1.9.24 ↔ Compose Compiler 1.5.14（严格匹配）

### GeoIP 数据库更新

`geoip.metadb` 和 `geosite.dat` 随 APK 分发（不需要 mihomo 联网下载）。如需更新：

```bash
# 从 MetaCubeX/meta-rules-dat 下载最新版本
curl -L -o app/src/main/assets/geoip.metadb \
  "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.metadb"
curl -L -o app/src/main/assets/geosite.dat \
  "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat"
```

---

## 使用说明

### 前置准备

1. **安装 Shizuku**：[Shizuku Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) 或 [GitHub Release](https://github.com/RikkaApps/Shizuku/releases)
2. **启动 Shizuku 服务**（手表无 ADB 时用 Wireless ADB）：
   - 手机连手表开 ADB：`adb connect <手表IP>:5555`
   - 启动 Shizuku：`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`
3. **给本 App 授权**：打开 Shizuku App → 授权 mihomo-watch-proxy

### 日常使用

1. 打开 App，确认 Shizuku 状态为 **READY**
2. 粘贴机场订阅链接
3. 点 **启动**（自动走反射通道，无需 UserService 绑定）
4. 点 **选择节点** → 测延迟 → 选节点
5. 完成。所有走系统代理的 App 都会通过 mihomo 出网

### 多订阅管理

- **保存**：粘贴订阅链接 → 点 **保存** → 输入名字
- **载入**：点已保存的订阅项 → 自动切换（热重载，不断流）
- **删除**：长按已保存的订阅项

### 开机自启

主界面打开 **开机自启** 开关。下次开机（Shizuku 就绪 + 有保存的订阅 URL）自动启动代理。

### TgWrist (Telegram) 配代理

TgWrist 的 MTProto **不走系统代理**，需要在 TgWrist 设置内手动配：
- 代理类型：SOCKS5
- 主机：`127.0.0.1`
- 端口：`7891`（独立 SOCKS5 端口，不是 7890）

---

## 代理兼容性

**走系统代理的 App**（直接生效）：
- ✅ 浏览器、WebView
- ✅ 大部分社交 App（Twitter、Instagram、Facebook）
- ✅ YouTube、Netflix 等流媒体
- ✅ Chrome 自带浏览器

**不走系统代理的 App**（需 App 内手动配代理）：
- ❌ **Telegram (TGWrist)**：MTProto 不走系统代理。需在 TGWrist 设置内配 SOCKS5 `127.0.0.1:7891`
- ❌ Flutter 应用（如部分第三方客户端）
- ❌ 游戏（P2P/UDP 为主）
- ❌ P2P 下载工具

---

## 已知限制

1. **只支持 Clash 格式订阅**：v2ray 格式请先用 [subconverter](https://github.com/tindy2013/subconverter) 转换
2. **mihomo 进程独立于 App**：App 退出不影响代理，但需要手动点 **停止** 才能关代理
3. **依赖 Shizuku**：Shizuku 服务挂了代理就断，需重启 Shizuku
4. **Wear OS 后台限制**：息屏 doze 模式下 mihomo 仍运行，但 CPU 可能限频，延迟略增
5. **只测 HTTP 延迟**：延迟测试用 `https://www.gstatic.com/generate_204`，UDP 节点延迟可能不准
6. **明文 HTTP 控制端口**：127.0.0.1:9090 用 HTTP（已配 network_security_config 放行）
7. **APK 体积偏大**：约 57MB（mihomo 44MB + geoip 8.8MB + geosite 4.2MB），国行手表必须内置 geo 数据
8. **touch bezel（边缘滑动滚动）未支持**：Wear Compose 1.3.1 不支持 rotary input，需升级到 1.4.0+（依赖 Kotlin 2.0 大版本升级）

---

## ⚠️ 注意事项（接手必读）

这一节记录了所有踩过的坑，**修改前务必通读**，避免重复踩坑。

### 1. 包名 `com.ys.proxy` 与 `java.net.Proxy` 类名冲突

包名最后一段是 `proxy`，与 `java.net.Proxy` 类名冲突。**必须用全路径**：

```kotlin
// ❌ 错误：Unresolved reference: NO_PROXY
.proxy(Proxy.NO_PROXY)

// ✅ 正确：全路径
.proxy(java.net.Proxy.NO_PROXY)
```

涉及文件：`MihomoApi.kt`、`SubscriptionManager.kt`。

### 2. OkHttp 默认走系统代理（致命陷阱）

OkHttp 默认走 `ProxySelector.getDefault()`。如果系统残留 `http_proxy=127.0.0.1:7890` 但 mihomo 已死，**所有 OkHttp 请求都会连不上**（包括访问 127.0.0.1:9090 也会被转发到死代理 7890）。

**必须显式 `proxy(java.net.Proxy.NO_PROXY)`** 给：
- `MihomoApi` 的 client（访问 mihomo 控制 API）
- `SubscriptionManager.directClient`（首次直连下载订阅）

### 3. 启动时清理残留 http_proxy

App 启动时 `refreshShizuku()` 检测到 mihomo 没在跑但 `http_proxy` 残留时，必须主动清理：

```kotlin
if (!running) {
    val proxy = runner("settings get global http_proxy")
    if (proxy.isNotBlank() && proxy.contains(":") && !proxy.contains(":0")) {
        runner("settings delete global http_proxy")
        runner("settings put global http_proxy :0")
    }
}
```

否则 OkHttp 直连下载订阅会走死代理 → "无法连接"。

### 4. mihomo 启动验证：进程在跑 ≠ API 就绪

`pgrep` 只能验证进程在跑，**不能验证 9090 端口已就绪**。mihomo 加载配置需要时间，端口起不来说明配置有问题。

`MihomoController.start()` 必须两步验证：
1. `pgrep -f mihomo` 验证进程
2. `netstat -tnl | grep ':9090'` 验证端口（轮询 10 次，每次 500ms）

`AppViewModel.doStartProxy()` 还要再调 `api.getProxies()` 确认 API 可用，失败就抛异常显示日志。

### 5. GeoIP 数据库必须随 APK 打包

mihomo 启动时需要 `geoip.metadb` 解析 `GEOIP,CN,DIRECT` 等规则。如果文件缺失/损坏，mihomo 会尝试从 github 下载，但**国内直连不通**（代理还没起来 → 鸡生蛋问题），下载卡住 → 9090 端口起不来 → "无法连接"。

`MihomoController.installGeodata()` 每次 start 前都重新安装 geo 数据，并删除可能损坏的旧文件（包括 `country.mmdb`、`geoip.dat` 等各种历史命名）。

配置注入 `geo-auto-update: false` + `geodata-mode: false` 禁止自动更新。

### 6. 二进制 assets 不能被 AAPT 压缩

`build.gradle.kts` 必须配置 `androidResources.noCompress`：

```kotlin
androidResources {
    noCompress += listOf("mihomo", "metadb", "dat", "mmdb")
}
```

否则 AAPT 压缩后 `AssetManager.open()` 读出来是压缩流，cp 到 `/data/local/tmp` 后文件损坏（mihomo 无法执行 / mmdb 无效）。

### 7. keystore 提交仓库保证签名一致

`app/debug.keystore` 直接提交仓库（非敏感信息）。`signingConfigs.debug.storeFile = file("debug.keystore")`。

**不要用 CI cache 缓存 `~/.android/debug.keystore`**，缓存恢复不可靠会导致签名不一致 → "无法覆盖安装：软件包与现有软件包存在冲突"。

release 也用 debug 签名，方便用户直接覆盖安装。

### 8. SIGKILL 而非 SIGTERM

`MihomoController.stop()` 和 `start()` 开头的杀进程都用 `pkill -9`（SIGKILL），不用默认 SIGTERM：

```bash
pkill -9 -f mihomo 2>/dev/null; sleep 0.5; pkill -9 -f mihomo 2>/dev/null; true
```

SIGTERM 退出有延迟，可能导致：
- 端口 7890 还被旧进程占用 → 新 mihomo 启动失败
- `isRunning` 误判还在跑

杀两次 + sleep 0.5 确保僵尸进程清干净。

### 9. refreshShizuku 必须异步

`pgrep` 走反射通道（`Shizuku.newProcess`）会起 shell 进程并 `waitFor`，**同步执行会阻塞主线程导致 App 卡顿**（用户反馈"打开一分钟内非常卡"）。

必须 `viewModelScope.launch(Dispatchers.IO) { ... }` 异步执行。

### 10. 切换订阅绝不能先停旧 mihomo

`switchSubscription()` 必须通过当前运行的 mihomo 代理下载新订阅 + 热重载，**不能先 stop**。

典型场景：用直连订阅 A 开代理，切换到需代理才能访问的订阅 B。如果先停 A，B 下载不了。

### 11. mihomo 是 shell 权限进程，App 卸载不影响

mihomo 通过 `nohup` 脱离 runner 进程生命周期，运行在 shell 权限下。**App 卸载/重装后 mihomo 仍在跑**，`http_proxy` 也仍在。

所以 `refreshShizuku()` **只更新 `isRunning` 状态**，不自动启动 `MihomoForegroundService`（避免每次打开 App 都弹通知让用户觉得"自动开启了"）。用户主动点"启动"时才弹通知。

### 12. nohup 脱离 runner 进程

mihomo 必须用 `nohup ... &` 脱离 Shizuku UserService 生命周期，否则 App 退出 mihomo 就被杀。

```bash
nohup /data/local/tmp/mihomo -d /data/local/tmp/mihomo_home > /data/local/tmp/mihomo_home/mihomo.log 2>&1 &
```

### 13. touch bezel 支持需要 Wear Compose 1.4.0+

Wear Compose 1.3.1 的 `ScalingLazyColumn` 不默认支持 rotary input（touch bezel）。`focusable()` modifier 在 1.3.1 上编译不过（`Unresolved reference: focusable`）。

**Galaxy Watch7 的 touch bezel** 不是物理旋转表圈，是屏幕边缘触摸滑动，系统转成 rotary input 事件。要支持需要升级到 Wear Compose 1.4.0+（依赖 Compose 1.7 + Kotlin 2.0 + Compose Compiler plugin，大版本升级）。

### 14. AGP 8.5.2 限制

release buildType 里**没有** `isLintVitalCheckEnabled` 属性，加了会编译错误。用 `lint { abortOnError = false; checkReleaseBuilds = false }` 替代。

### 15. GitHub Token（用于拉 Actions 构建日志）

如果需要通过 GitHub API 拉 Actions 构建日志精确定位编译错误，需要 token。**用户提供的 token 必须牢记**，不要每次都问。

---

## 接手指南

### 改业务逻辑

90% 的修改都在 [AppViewModel.kt](app/src/main/java/com/ys/proxy/AppViewModel.kt)。这个文件是核心，所有 state 和业务流程都在这里。

### 改 UI

[MainActivity.kt](app/src/main/java/com/ys/proxy/MainActivity.kt) 用 Wear OS Compose（`ScalingLazyColumn` + `Chip`）。注意 Wear OS 屏幕小，文字一般 10-13sp。

### 关键设计决策（不要乱改）

1. **`getRunner()` 优先 UserService，回退反射**：不要删反射通道，32 位系统上 UserService 经常绑不上
2. **`switchSubscription` 绝不先停 mihomo**：用旧代理下载新订阅 + 热重载，避免需要代理的订阅下载失败
3. **`doStartProxy` 必须等 API 就绪**：mihomo 启动后 API 需要几秒才能响应，立即调 API 会失败
4. **`SubscriptionManager.download(viaProxy=true)`**：切换订阅时必须走当前代理下载
5. **`use32BitAppProcess` 反射**：纯 32 位系统上其实是 no-op（`app_process32` 不存在），但保留无害
6. **mihomo 路径 `/data/local/tmp/`**：shell 可写可执行，不能用 App 私有目录
7. **`nohup` 脱离 runner 进程**：mihomo 必须脱离 Shizuku UserService 生命周期，否则 App 退出 mihomo 就被杀
8. **`UserServiceArgs.version(4)`**：升级 UserService 时必须 +1，强制 Shizuku 丢弃旧进程
9. **`installGeodata()` 每次 start 都重新安装**：覆盖可能损坏的旧 geo 文件
10. **OkHttp 显式 NO_PROXY**：所有 client 都要，否则走死代理

### 关键常量

```kotlin
// MihomoController.kt
const val MIHOMO_BIN = "/data/local/tmp/mihomo"
const val MIHOMO_HOME = "/data/local/tmp/mihomo_home"
const val PROXY_ADDR = "127.0.0.1:7890"     // 系统代理地址 (mixed-port)
const val API_BASE = "http://127.0.0.1:9090" // mihomo 控制 API
// socks-port 7891 在 SubscriptionManager.injectControllerConfig 注入

// MihomoApi.kt
const val secret = "watch123"                // mihomo API 密码（订阅注入时也用这个）
```

### 调试技巧

1. **诊断面板**：主界面点 **诊断** 看 Shizuku 状态、反射测试、bind 失败原因
2. **mihomo 日志**：`adb shell tail -f /data/local/tmp/mihomo_home/mihomo.log`（需 shell 权限）
3. **看全局代理**：`adb shell settings get global http_proxy`
4. **看 mihomo 进程**：`adb shell pgrep -f mihomo`
5. **看 9090 端口**：`adb shell netstat -tnl | grep 9090`
6. **手动停止 mihomo**：`adb shell pkill -9 -f mihomo && adb shell settings delete global http_proxy`
7. **看 geo 文件**：`adb shell ls -la /data/local/tmp/mihomo_home/`（geoip.metadb 应约 8.8MB）

### 测试反射通道

主界面 **诊断** → **测试反射**。返回 `uid=2000(shell)` 即成功，反射通道可用。

---

## 常见问题排查

### 启动失败：failed to connect to xxx

订阅链接本身需要代理才能访问。换个能直连的订阅链接，或者用其他设备下载订阅内容传到手表。

### 选择节点失败：CLEARTEXT communication to 127.0.0.1 not permitted

Android 9+ 默认禁明文 HTTP。已通过 `network_security_config.xml` 放行 127.0.0.1。如果还报错，检查 [network_security_config.xml](app/src/main/res/xml/network_security_config.xml) 是否正确加载。

### 选择节点失败：mihomo 可能尚未就绪

mihomo 启动后 API 需要几秒才能响应。当前实现已加 10 秒轮询（`doStartProxy`），如果还失败可能是订阅配置有问题，看 mihomo 日志。

### UserService 绑定失败

32 位 Wear OS 已知问题。**不影响使用**，反射通道仍可用。直接点 **启动** 即可。

### 切换订阅后节点没变

切换订阅用热重载（`PUT /configs?force=true`），节点列表会清空。重新点 **选择节点** 加载新订阅的节点。

### 重启手表后代理失效

打开 **开机自启** 开关。注意 Shizuku 也需要重启（手表重启后 Shizuku 服务会断，需重新用 ADB 启动）。

### 无法覆盖安装：软件包与现有软件包存在冲突

签名不一致。确认 `app/debug.keystore` 是仓库里的同一个文件，`signingConfigs.debug.storeFile = file("debug.keystore")`。**不要用 CI cache 缓存 keystore**。

### 启动卡住 / 9090 端口起不来 / "MMDB invalid, remove and download"

GeoIP 数据库缺失或损坏。检查：
1. `app/src/main/assets/geoip.metadb` 存在且约 8.8MB
2. `app/src/main/assets/geosite.dat` 存在且约 4.2MB
3. `build.gradle.kts` 的 `noCompress` 包含 `metadb`、`dat`
4. `MihomoController.installGeodata()` 在 `start()` 里被调用
5. `SubscriptionManager.injectControllerConfig()` 注入了 `geo-auto-update: false`

### App 打开非常卡

`refreshShizuku` 同步执行 pgrep 阻塞主线程。确认在 `Dispatchers.IO` 异步执行。

---

## 致谢

- [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo) - mihomo (Clash Meta) 内核
- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) - Shizuku 特权服务框架
- [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) - GeoIP/GeoSite 数据库

## License

MIT
