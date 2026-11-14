#!/usr/bin/env bash
#
# Music Killer 一键部署脚本
#
# 用法：
#   ./deploy.sh            # 构建并启动（首次部署 / 更新都适用）
#   ./deploy.sh logs       # 查看后端日志
#   ./deploy.sh restart    # 重启
#   ./deploy.sh stop       # 停止
#   ./deploy.sh down       # 停止并删除容器（保留数据库数据）
#   ./deploy.sh destroy    # 停止并删除容器 + 数据库数据（谨慎）
#   ./deploy.sh status     # 查看状态
#
set -euo pipefail

cd "$(dirname "$0")"

COMPOSE="docker compose"

log()  { printf '\033[32m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '\033[33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 前置检查
need_docker() {
  command -v docker >/dev/null 2>&1 || die "未找到 docker，请先安装 Docker"
  docker compose version >/dev/null 2>&1 || die "未找到 docker compose 插件"
  docker info >/dev/null 2>&1 || die "Docker 未运行，或当前用户没有权限（可尝试 sudo）"
}

ensure_env() {
  if [ ! -f .env ]; then
    warn ".env 不存在，正在从 .env.example 生成，请记得修改数据库密码"
    cp .env.example .env
  fi
}

wait_backend() {
  log "等待后端启动（最多 180 秒）..."
  local i
  for i in $(seq 1 90); do
    if docker logs mk-backend 2>&1 | grep -q "Started QYMusicApplication"; then
      log "后端已启动，耗时约 $((i * 2)) 秒"
      return 0
    fi
    if ! docker ps --format '{{.Names}}' | grep -q '^mk-backend$'; then
      die "mk-backend 容器已退出，请执行 ./deploy.sh logs 查看原因"
    fi
    sleep 2
  done
  warn "等待超时，容器可能仍在启动中，请执行 ./deploy.sh logs 查看"
}

# ---------------------------------------------------------------- 主流程
case "${1:-up}" in
  up)
    need_docker
    ensure_env
    log "构建镜像并启动服务（首次构建需要下载 Maven 依赖，约 3-8 分钟）"
    $COMPOSE up -d --build
    wait_backend
    echo
    $COMPOSE ps
    echo
    IP=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
    log "部署完成，接口地址：http://${IP:-<服务器IP>}:8002"
    log "健康检查：curl -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:8002/rank/menu   （未签名返回 500 属于正常）"
    ;;
  logs)
    $COMPOSE logs -f --tail=200 backend
    ;;
  restart)
    need_docker
    $COMPOSE restart
    wait_backend
    ;;
  stop)
    need_docker
    $COMPOSE stop
    log "已停止（容器保留，数据保留）"
    ;;
  down)
    need_docker
    $COMPOSE down
    log "已删除容器，数据库数据卷 mk-mysql-data 仍保留"
    ;;
  destroy)
    need_docker
    printf '这会删除数据库数据，确定吗？输入 yes 继续：'
    read -r ans
    [ "$ans" = "yes" ] || die "已取消"
    $COMPOSE down -v
    log "已删除容器与数据卷"
    ;;
  status)
    need_docker
    $COMPOSE ps
    ;;
  *)
    die "未知命令：$1（可用：up / logs / restart / stop / down / destroy / status）"
    ;;
esac
