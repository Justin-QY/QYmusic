# QY MUSIC

一个简洁好用的第三方音乐播放器，包含 Android 客户端与服务端，支持网易云 / 酷我双曲库、无损音质、歌词翻译与在线更新。

> 仅供个人学习与技术研究使用，请尊重音乐版权，支持正版。

- 客户端：Kotlin（Android，minSdk 24 / targetSdk 36）
- 服务端：Java 17 + Spring Boot 3.5 + MyBatis-Plus + MySQL 8 + Redis 7
- 当前版本：**1.0.0（versionCode 15）**

---

## 功能

### 曲库与播放
- **双曲库切换**：设置页可在「网易云 / 酷我」之间切换，首页推荐、排行榜、歌手、歌单、搜索、歌词整体换源
- **多路播放解析**：洛雪 LX 脚本（服务端内嵌）/ 酷我同名直链 / 第三方聚合接口并行解析，谁先给出可用地址用谁
- **音质选择**：128k / 192k / 320k / 无损 FLAC / Hi-Res，默认 128k；拿不到所选音质自动降级
- **移动网络省流量**：开启后使用流量时最高播放 128k
- 播放队列、循环/单曲循环、后台播放、通知栏 / 锁屏 / 蓝牙控制

### 第三方音源（可选）
- 内置「落雪 LX 脚本」解析，默认启用
- 可选接入 **ChKSz API**：默认关闭，开启时需填写自行申请的 `apikey`（仅保存在本机）
- 开启后网易云歌曲优先走第三方接口，失败自动回落到落雪脚本

### 内容
- 首页推荐、排行榜（官方/特色/场景/语言/全球）、歌手、歌单、搜索（歌曲/专辑/歌手/歌单）
- 歌词：支持原文与翻译，可切换是否显示翻译
- 收藏、播放历史、下载（含歌词文件）

### 体验
- 首次启动「用户协议与隐私政策」，需阅读 5 秒后方可同意，不同意直接退出
- 设置页分组：当前配置 / 曲库 / 播放 / 播放音源 / 通用 / 关于与联系
- 主题：跟随系统 / 浅色 / 深色
- 一键清除缓存（音频缓存与图片缓存）
- **强制更新**：服务端下发最低版本，低版本客户端无法继续使用，且服务端不返回任何业务数据

---

## 目录结构

```
music-killer-android/    Android 客户端（Kotlin）
music-killer-java/       服务端（Maven 多模块）
deploy/                  Docker 部署文件
```

---

## 客户端构建

环境：JDK 17、Android SDK（compileSdk 36）、Gradle 8.13。

```bash
cd music-killer-android
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

- 包名：`com.qymusic.app`
- 服务端地址在 `app/src/main/java/com/qymusic/app/config/Api.kt` 的 `BASE_API` 中配置
- 签名配置读取项目根目录的 `keystore.properties`（不入库），格式：

```properties
storeFile=/path/to/your.jks
storePassword=******
keyAlias=******
keyPassword=******
```

## 下载

- **主线路**：`http://168.110.9.35:8002/app/qy-music-1.0.1.apk`
- **备用线路**：[GitHub Releases](https://github.com/Justin-QY/QYmusic/releases)

客户端内置更新时优先使用主线路，失败会自动切换到 GitHub Release 备用地址。

---

## 服务端部署

```bash
cd music-killer-java
mvn -Pprod -DskipTests package

# 或使用 Docker
cd ../deploy
chmod +x deploy.sh
./deploy.sh
```

默认端口 `8002`，需要 MySQL 与 Redis。

### 关键配置 `application.yml`

```yaml
api:
  auth:
    salt: MusicKillerNativeSalt   # 客户端签名盐，需与客户端一致

app:
  min-version-code: 15            # 强制更新：低于该 versionCode 不返回业务数据
  update:
    dir: /opt/music-killer/update # update.json 与 apk 所在目录
```

更新目录结构：

```
/opt/music-killer/update/
├── update.json
└── qy-music-1.0.0.apk
```

`update.json` 示例：

```json
{
  "versionCode": 15,
  "versionName": "1.0.0",
  "url": "/app/qy-music-1.0.0.apk",
  "size": 14500000,
  "md5": "…",
  "minVersionCode": 15,
  "force": true,
  "changelog": "更新说明"
}
```

---

## 主要接口

| 接口 | 说明 |
|---|---|
| `GET /home/data` | 首页数据 |
| `GET /search/netease` | 网易云搜索 |
| `GET /search/kuwo` | 酷我搜索 |
| `GET /kuwo/**` | 酷我曲库（榜单/歌单/歌手/搜索/歌词） |
| `GET /play/info` | 解析播放地址（支持 `bridge`、`source`） |
| `GET /lyric/{id}?translate=` | 歌词，`translate=true` 合并翻译 |
| `GET /lx/scripts` | 服务端已加载的洛雪脚本 |
| `GET /app/update.json` | 客户端更新信息（无需签名） |

接口默认需要 `Sign / Timestamp / Version` 请求头，算法为 `md5(salt + timestamp + versionCode)`。

---

## 版本历史

### 1.0.0（正式版）
- 品牌与包名统一为 QY MUSIC（`com.qymusic.app`），移除历史命名痕迹
- 设置页按设计稿重做：当前配置、曲库、播放、播放音源、通用、关于与联系
- 新增默认音质页（128k / 320k / 无损 / Hi-Res）与播放音源页（第三方开关、密钥、连接测试、脚本列表）
- 新增主题切换、清除缓存、移动网络省流量、歌词翻译开关
- 切换曲库后自动回到并刷新首页
- 修复桌面图标留白 / 透明边
- 修复通知栏、锁屏只显示应用名而不显示歌曲信息
- 开启 R8 混淆与资源压缩，降低被直接反编译复用的风险
- 通知栏 / 锁屏展示歌曲名、歌手与封面

### 1.9.3
- 修复网易云歌曲没有歌词（正确解析服务端歌词数据，并直连兜底）

### 1.9.2
- 设置页拆分「落雪 LX 脚本 / 第三方音源」，新增 ChKSz API 接入
- 新增强制更新：低版本无法使用，服务端拒绝返回数据

### 1.9.1
- 音源脚本改为下拉仅显示名称
- 新增播放音质选择（128k / 192k / 320k / 无损，默认 128k）

### 1.9
- 修复收藏表缺少 `source` 列导致的搜索崩溃
- 底部播放栏仅在播放时显示，并同步歌曲信息
- 移除未接入的 QQ 音乐入口

---

## 开源与致谢

本项目在实现过程中参考并使用了以下开源项目与技术：

- [music-killer](https://github.com/guang518/music-killer)（Apache-2.0）：服务端与客户端的基础结构
- [LX Music 落雪音乐](https://github.com/lyswhut/lx-music-desktop)：音源脚本运行机制与脚本生态
- [ChKSz API](https://api.chksz.com)：可选第三方音乐解析接口
- [drakeet / drake 系列](https://github.com/liangjingkanji)：Net、BRV、Tooltip、Engine
- AndroidX、Media3、Material Components、Glide、LitePal、KotlinX Serialization

感谢以上项目的作者与贡献者。相关代码遵循其原始开源许可（Apache-2.0），详见仓库 `LICENSE`。

## 免责声明

本项目仅用于学习与技术交流，不提供任何音源存储与分发。所有音乐信息、歌词与播放地址均来自第三方公开接口，版权归各平台与权利人所有。请勿用于商业用途，并支持正版音乐。

如权利人认为内容侵权，可通过 `qyder8@gmail.com` 联系处理。
