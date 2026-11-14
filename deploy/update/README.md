# App 在线更新目录

放两个文件，后端会自动对外提供：

- `update.json`：版本信息
- `qy-music-x.y.apk`：安装包本体（`/app/qy-music-x.y.apk`）

`update.json` 格式：

```json
{
  "versionCode": 5,
  "versionName": "1.4",
  "url": "/app/qy-music-1.4.apk",
  "size": 14480215,
  "md5": "安装包 MD5，小写十六进制",
  "changelog": "1. 新增设置页\n2. 支持在线更新",
  "updateTime": "2026-11-13",
  "force": false
}
```

发布新版本的步骤：

```bash
# 1. 上传 APK
scp QY-MUSIC-1.5.apk root@<server>:/opt/music-killer/update/qy-music-1.5.apk

# 2. 生成 MD5 / 大小
md5sum /opt/music-killer/update/qy-music-1.5.apk
stat -c%s /opt/music-killer/update/qy-music-1.5.apk

# 3. 修改 update.json（versionCode 必须大于当前线上版本）
vi /opt/music-killer/update/update.json
```

客户端启动时会在后台检查（6 小时一次），设置页也有「检查更新」可手动触发。
下载后会校验 MD5 与签名，签名不一致的安装包不会被安装。
