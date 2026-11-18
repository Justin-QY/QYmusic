# QPlayer Kuwo source plugin

<p><a href="README.md">简体中文</a> · <b>English</b></p>

An independent QPlayer source plugin (ABI 1.0). Search, playlists, stream URLs and
lyrics stay in the plugin; QPlayer core does not ship Kuwo endpoints.

Protocol mapping follows [go-music-dl](https://github.com/guohuiyuan/go-music-dl) / `music-lib`.
Not affiliated with Kuwo. No audio or credentials are distributed.

## Build

```bash
./scripts/package.sh
python3 scripts/verify-package.py dist/*.qplug
```

Import the `.qplug` from **Settings → Source plugins**.
