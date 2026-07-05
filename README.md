# mihomo-watch-proxy

为 **三星 Galaxy Watch7 国行版**（Wear OS 5, 32 位 armeabi-v7a, API 34）设计的 mihomo (Clash Meta) 代理 App。

> ⚠️ 国行三星手表禁用了 `VpnService`，传统 Clash/Shadowrocket 思路完全不适用。本项目通过 **Shizuku + 系统代理**绕过限制。

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
│  Wear OS App (本 App, 普通权限)                  │
│    ↓ 通过 Shizuku binder                         │
│  Shizuku Service (shell 权限, uid=2000)          │
│    ↓ 执行 shell 命令                              │
│  mihomo 进程 (shell 启动, /data/local/tmp/)      │
│    ↓ 监听 127.0.0.1:7890 (mixed-port)            │
│    ↓ 监听 127.0.0.1:9090 (external-controller)   │
│  系统全局代理: settings put global http_proxy    │
│    ↓                                              │
│  所有遵守系统代理的 App → 走 mihomo → 出网        │
└─────────────────────────────────────────────────┘
```

### 双通道设计（关键）

32 位 Wear OS 上 Shizuku 的 `bindUserService` 经常静默失败（详见 [ShizukuManager.kt](app/src/main/java/com/mihomo/watch/ShizukuManager.kt) 注释）。本项目设计了双通道：

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

---

## 项目结构

```
app/src/main/
├── java/com/mihomo/watch/
│   ├── MainActivity.kt           # Wear OS Compose UI 主入口
│   ├── AppViewModel.kt           # 状态管理 + 业务编排（最核心）
│   ├── ShizukuManager.kt         # Shizuku 连接 + 反射通道
│   ├── MihomoController.kt       # mihomo 二进制/配置/启动/停止
│   ├── MihomoApi.kt              # mihomo RESTful API 客户端
│   ├── SubscriptionManager.kt    # 订阅下载 + 配置注入
│   ├── SubscriptionStore.kt      # 多订阅本地存储 (SharedPreferences + JSON)
│   └── WatchUserService.kt       # Shizuku UserService 实现 (AIDL)
├── aidl/com/mihomo/watch/
│   └── IWatchService.aidl        # UserService 接口定义
├── assets/
│   └── mihomo                    # mihomo armv7 二进制 (Actions 构建时下载)
├── res/xml/
│   └── network_security_config.xml  # 允许明文 HTTP 访问 127.0.0.1
└── AndroidManifest.xml           # 声明 watch feature + Shizuku provider
```

### 模块职责

| 文件 | 职责 |
|------|------|
| [AppViewModel.kt](app/src/main/java/com/mihomo/watch/AppViewModel.kt) | 所有 UI state + 业务流程编排（启动/停止/切换订阅/测延迟）。**改业务逻辑主要改这里** |
| [ShizukuManager.kt](app/src/main/java/com/mihomo/watch/ShizukuManager.kt) | Shizuku 状态检测、UserService 绑定、反射通道、诊断信息 |
| [MihomoController.kt](app/src/main/java/com/mihomo/watch/MihomoController.kt) | mihomo 二进制释放、配置安装、进程启动/停止、全局代理设置 |
| [MihomoApi.kt](app/src/main/java/com/mihomo/watch/MihomoApi.kt) | 调 mihomo 的 9090 API：查询节点、选节点、测延迟、热重载 |
| [SubscriptionManager.kt](app/src/main/java/com/mihomo/watch/SubscriptionManager.kt) | 下载订阅（支持 viaProxy 走当前代理）+ 注入控制面板配置 |
| [SubscriptionStore.kt](app/src/main/java/com/mihomo/watch/SubscriptionStore.kt) | 多订阅持久化（JSON 数组存 SharedPreferences） |

---

## 构建方式

### 推荐：GitHub Actions（无需本地环境）

push 到 `main` 分支自动触发 [.github/workflows/build.yml](.github/workflows/build.yml)，构建产物在 Actions → Artifacts 下载。

工作流自动完成：
1. 下载 MetaCubeX/mihomo 最新 android-armv7 二进制
2. 解压到 `app/src/main/assets/mihomo`
3. `./gradlew assembleDebug` 构建 debug APK
4. 上传 artifact（保留 90 天）

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
- `signingConfig = debug`：release 也用 debug 签名方便直接装（可改）

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

---

## 代理兼容性

**走系统代理的 App**（直接生效）：
- ✅ 浏览器、WebView
- ✅ 大部分社交 App（Twitter、Instagram、Facebook）
- ✅ YouTube、Netflix 等流媒体
- ✅ Chrome 自带浏览器

**不走系统代理的 App**（需 App 内手动配代理）：
- ❌ **Telegram (TGWrist)**：MTProto 不走系统代理。需在 TGWrist 设置内配 SOCKS5 `127.0.0.1:7890`
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

---

## 接手指南

### 改业务逻辑

90% 的修改都在 [AppViewModel.kt](app/src/main/java/com/mihomo/watch/AppViewModel.kt)。这个文件是核心，所有 state 和业务流程都在这里。

### 改 UI

[MainActivity.kt](app/src/main/java/com/mihomo/watch/MainActivity.kt) 用 Wear OS Compose（`ScalingLazyColumn` + `Chip`）。注意 Wear OS 屏幕小，文字一般 10-13sp。

### 关键设计决策（不要乱改）

1. **`getRunner()` 优先 UserService，回退反射**：不要删反射通道，32 位系统上 UserService 经常绑不上
2. **`switchSubscription` 绝不先停 mihomo**：用旧代理下载新订阅 + 热重载，避免需要代理的订阅下载失败
3. **`doStartProxy` 必须等 API 就绪**：mihomo 启动后 API 需要几秒才能响应，立即调 API 会失败
4. **`SubscriptionManager.download(viaProxy=true)`**：切换订阅时必须走当前代理下载
5. **`use32BitAppProcess` 反射**：纯 32 位系统上其实是 no-op（`app_process32` 不存在），但保留无害
6. **mihomo 路径 `/data/local/tmp/`**：shell 可写可执行，不能用 App 私有目录
7. **`nohup` 脱离 runner 进程**：mihomo 必须脱离 Shizuku UserService 生命周期，否则 App 退出 mihomo 就被杀
8. **`UserServiceArgs.version(4)`**：升级 UserService 时必须 +1，强制 Shizuku 丢弃旧进程

### 关键常量

```kotlin
// MihomoController.kt
const val MIHOMO_BIN = "/data/local/tmp/mihomo"
const val MIHOMO_HOME = "/data/local/tmp/mihomo_home"
const val PROXY_ADDR = "127.0.0.1:7890"     // 系统代理地址
const val API_BASE = "http://127.0.0.1:9090" // mihomo 控制 API

// MihomoApi.kt
const val secret = "watch123"                // mihomo API 密码（订阅注入时也用这个）
```

### 调试技巧

1. **诊断面板**：主界面点 **诊断** 看 Shizuku 状态、反射测试、bind 失败原因
2. **mihomo 日志**：`adb shell tail -f /data/local/tmp/mihomo_home/mihomo.log`（需 shell 权限）
3. **看全局代理**：`adb shell settings get global http_proxy`
4. **看 mihomo 进程**：`adb shell pgrep -f mihomo`
5. **手动停止 mihomo**：`adb shell pkill -f mihomo && adb shell settings delete global http_proxy`

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

---

## 致谢

- [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo) - mihomo (Clash Meta) 内核
- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) - Shizuku 特权服务框架

## License

MIT
