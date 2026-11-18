# QPlayer Migu source plugin

<p><a href="README.md">简体中文</a> · <b>English</b></p>

Independent QPlayer source plugin (ABI 1.0). Protocol mapping follows
[go-music-dl](https://github.com/guohuiyuan/go-music-dl) / `music-lib`.
Not affiliated with Migu Music.

Native song IDs are `contentId|resourceType|formatType`.

## Build

```bash
./scripts/package.sh
python3 scripts/verify-package.py dist/*.qplug
```
