# QPlayer 酷我音乐音源插件

<p><b>简体中文</b> · <a href="README.en.md">English</a></p>

QPlayer 的独立音源插件，实现公开的 JavaScript 插件 ABI（apiVersion 1.0）。
搜索、歌单、播放地址与歌词均在插件内完成，QPlayer 核心不包含酷我接口。

解析逻辑参考 [go-music-dl](https://github.com/guohuiyuan/go-music-dl) / `music-lib` 的 kuwo 实现。
本项目与酷我音乐没有隶属或合作关系，不分发音频或账号凭据。使用者需自行遵守服务条款与当地法律。

## 功能

| 能力 | 说明 |
|---|---|
| `searchSongs` / `searchAlbums` | 搜索歌曲与专辑 |
| `songDetails` | 按 rid 取详情 |
| `playlistDetails` / `albumDetails` | 歌单、专辑曲目 |
| `home` | 热门推荐歌单 |
| `resolveStream` | `mobi.s` 播放地址，失败时回退网页 playUrl |
| `lyrics` | 逐行 LRC |
| `login` / `account` | 可选 Cookie，未登录也可搜索播放部分曲目 |

## 构建

```bash
./scripts/package.sh
python3 scripts/verify-package.py dist/*.qplug
```

在 QPlayer「设置 → 音源插件 → 导入插件包」选择生成的 `.qplug`。

完整 ABI 见 [插件模板](https://github.com/TIMER-err/qplayer-plugin-template/blob/main/docs/ABI.md)。
