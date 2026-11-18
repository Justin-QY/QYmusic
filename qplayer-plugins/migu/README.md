# QPlayer 咪咕音乐音源插件

<p><b>简体中文</b> · <a href="README.en.md">English</a></p>

QPlayer 的独立音源插件，实现公开的 JavaScript 插件 ABI（apiVersion 1.0）。
解析逻辑参考 [go-music-dl](https://github.com/guohuiyuan/go-music-dl) / `music-lib` 的 migu 实现。

本项目与咪咕音乐没有隶属或合作关系，不分发音频或账号凭据。使用者需自行遵守服务条款与当地法律。

## 功能

| 能力 | 说明 |
|---|---|
| `searchSongs` / `searchAlbums` | 搜索歌曲与专辑 |
| `songDetails` | 按 contentId 取详情 |
| `playlistDetails` / `albumDetails` | 歌单、专辑曲目 |
| `home` | 以「华语」关键词拉取推荐歌单 |
| `resolveStream` | `listenSong.do` 302 后的播放地址 |
| `lyrics` | 下载 lrcUrl 明文 LRC |
| `login` / `account` | 可选 Cookie |

歌曲原生 ID 为 `contentId|resourceType|formatType`，与 music-lib 一致。

## 构建

```bash
./scripts/package.sh
python3 scripts/verify-package.py dist/*.qplug
```

完整 ABI 见 [插件模板](https://github.com/TIMER-err/qplayer-plugin-template/blob/main/docs/ABI.md)。
