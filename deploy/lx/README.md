# 外部音源脚本目录（热替换）

后端内置了几个洛雪（lx-music）v4 格式的音源脚本，随 jar 一起发布，开箱即用。

需要临时换脚本 / 加新脚本时，不用重新打包发版：

1. 把 `.js` 脚本放到本目录（文件名即脚本 id，例如 `hyw.js`）。
2. 与内置脚本同名会直接覆盖内置版本。
3. 调 `POST /lx/reload` 让引擎重新加载（需带正常签名头）。
4. `GET /lx/status` 看脚本数量，`GET /lx/scripts` 看每个脚本声明的音源与音质。

也可以在 App 的「设置 → 音源脚本」里查看当前生效的脚本与作者，点「刷新音源」让服务端重新扫描本目录。

## 当前内置脚本

随 jar 发布：`hyw`、`stellarwave`、`fish`、`quandou`、`nianxin`。

> `nianxin` 在当前网络环境下所有解析都失效，已在 `application.yml` 里通过
> `lx.disabled: nianxin` 停用（文件保留，后续恢复可用时删掉该配置即可）。

## 当前外置脚本（本目录）

- `xinghai.js` 星海音乐源 v3.2.19
- `kxh.js` K×H 测试 v1.7.17
- `helloworld.js` HelloWorld 260925

外置脚本会覆盖同名内置脚本；不需要时直接删文件并调用 `/lx/reload`。


脚本运行在 GraalJS 沙箱里：不能读文件、起线程、起进程，也不能通过 `Java.type`
访问宿主类；所有网络请求都从宿主统一出口发出，并拒绝访问内网地址。
