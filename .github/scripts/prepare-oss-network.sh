#!/usr/bin/env bash
# 只调整临时 Linux runner 的新连接，不安装内核、不修改宿主服务器。
set -euo pipefail

previous=$(sysctl -n net.ipv4.tcp_congestion_control)
if [[ "$previous" != bbr ]]; then
  sudo modprobe tcp_bbr
  sudo sysctl -w net.ipv4.tcp_congestion_control=bbr
fi
current=$(sysctl -n net.ipv4.tcp_congestion_control)
[[ "$current" == bbr ]]
printf 'OSS 上传网络已准备：%s → %s\n' "$previous" "$current"
