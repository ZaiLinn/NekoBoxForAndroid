# NekoBox Z

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Releases](https://img.shields.io/github/v/release/ZaiLinn/NekoBoxForAndroid)](https://github.com/ZaiLinn/NekoBoxForAndroid/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

基于 [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) 的修改版，一款使用 sing-box 的 Android 通用代理软件。重点改善息屏、休眠和网络切换后的连接稳定性。

A fork of [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) (sing-box based proxy toolchain for Android), focused on connection stability after screen off, device sleep and network changes.

## 下载 / Downloads

[GitHub Releases](https://github.com/ZaiLinn/NekoBoxForAndroid/releases)

一般手机选 `arm64-v8a`，很老的 32 位手机选 `armeabi-v7a`，模拟器选 `x86` / `x86_64`。

包名为 `io.github.zailinn.nekobox`，可以和原版 NekoBox（`moe.nb4a`）同时安装。节点和设置可以在原版中备份（工具 → 备份）后导入。两个 App 同一时间只能有一个使用 VPN 模式。

## 与原版的区别 / Changes from upstream

### 连接稳定性

- 修复息屏、休眠后连不上需要手动重连的问题：
  - 重置连接时会重建出站连接池（mux、QUIC 等）并清理 DNS 缓存，原版只关闭已有连接
  - 以网络本身判断网络切换，Wi-Fi 断开重连后也会重置连接，原版只比较网卡名
  - 息屏 2 分钟以上再亮屏时重置连接，覆盖部分系统息屏断网但不进入 Doze 的情况
  - "唤醒时重置出站连接"默认开启
- TCP 保活从空闲 10 分钟改为 30 秒，避免长连接被运营商 NAT 悄悄回收
- gRPC / h2 传输开启健康检查，连接失效约 30 秒内自动重建
- VLESS / VMess / Trojan 的 TLS 节点未设置指纹时默认使用 `chrome` uTLS 指纹
- VLESS + Vision 未选数据包编码时使用 xudp，修复所有 UDP（QUIC、游戏等）都不通的问题
- 修复后台服务的若干崩溃和竞态问题（停止时连接流程未取消、休眠广播导致崩溃等）

### Root 透明代理

设置 → 运行模式 → Root 透明代理。需要 root 权限，不占用系统 VPN：

- 通过 iptables 把 TCP 和 DNS 重定向给内核，支持分应用代理设置
- 核心不以 root 运行，无法使用 TPROXY，所以 DNS 以外的 UDP 不经过代理；QUIC（UDP 443）会被拒绝，让 App 改用 TCP
- 内核不支持 IPv6 NAT 时，被代理 App 的 IPv6 流量会被拒绝，避免泄露
- 透明代理端口默认 9280，DNS 使用端口 + 1
- 停止服务时自动清除规则；如果 App 被强行结束，规则会在下次启动服务时清除

### 其他

- 息屏时暂停流量统计轮询，降低耗电
- 去掉菜单中的推广、文档入口和关于页面的捐赠、Telegram
- 连接测试失败时正确显示失败，而不是一直停在"测试中"

## 支持的代理协议 / Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess
* Trojan
* VLESS
* AnyTLS
* ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard
* Trojan-Go (trojan-go-plugin)
* NaïveProxy (naive-plugin)
* Mieru (mieru-plugin)

Trojan-Go、NaïveProxy、Mieru 需要另外安装原版的[插件](https://matsuridayo.github.io/nb4a-plugin/)。

Trojan-Go, NaïveProxy and Mieru require the upstream [plugins](https://matsuridayo.github.io/nb4a-plugin/).

## 支持的订阅格式 / Supported Subscription Format

* 一些广泛使用的格式（如 Shadowsocks、ClashMeta 和 v2rayN）
* sing-box 出站

仅支持解析出站，即节点。分流规则等信息会被忽略。

* Some widely used formats (like Shadowsocks, ClashMeta and v2rayN)
* sing-box outbound

Only resolving outbound, i.e. nodes, is supported. Information such as diversion rules are ignored.

## 构建 / Build

使用 GitHub Actions 构建：

- **Preview Build**：手动运行，构建产物（APK）挂在运行页面底部的 Artifacts 中
- **Release Build**：手动运行并填写版本号，自动发布到 Releases

签名：如果在仓库 Secrets 中设置了 `KEYSTORE_BASE64`（keystore 文件的 base64）、`KEYSTORE_PASS`、`ALIAS_NAME`、`ALIAS_PASS`，则使用该密钥签名；否则自动生成密钥并保存在 Actions 缓存中。缓存按分支隔离，且 7 天未使用会被清除，清除后签名会变化、需要卸载重装，长期使用建议设置 Secrets。

sing-box 内核在构建时拉取源码，`buildScript/lib/core/patches/` 下的补丁会自动应用。

## Credits

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
