#!/usr/bin/env bash
# deploy.sh — раскатка интеграции mynotify на хост HA (HAOS):
# кладёт custom_components/mynotify в /config/custom_components/mynotify.
# Параметры — из переменных окружения или локального deploy.env (не в git):
#   HA_KEY=<путь к ssh-ключу>  HA_HOST=<адрес хоста HA>  [HA_USER=hassio]
# НОВЫЕ интеграции HA подхватывает только после рестарта Core — рестарт
# автоматически НЕ делается, скрипт только напоминает.
set -euo pipefail
cd "$(dirname "$0")"
[ -f deploy.env ] && source deploy.env

KEY="${HA_KEY:?укажи HA_KEY=<путь к ssh-ключу>}"
HOST="${HA_HOST:?укажи HA_HOST=<адрес хоста HA>}"
USER="${HA_USER:-hassio}"
SRC="custom_components/mynotify"
DEST="/config/custom_components/mynotify"

SSH="ssh -i $KEY -o StrictHostKeyChecking=accept-new -o ConnectTimeout=4 $USER@$HOST"

echo "==rsync $SRC -> $HOST:$DEST=="
$SSH "sudo mkdir -p $DEST"
rsync -a --delete \
  --exclude='__pycache__' --exclude='*.pyc' \
  -e "ssh -i $KEY -o StrictHostKeyChecking=accept-new -o ConnectTimeout=4" \
  --rsync-path="sudo rsync" \
  "$SRC/" "$USER@$HOST:$DEST/"
$SSH "sudo chown -R root:root $DEST && sudo find $DEST -name __pycache__ -exec rm -rf {} + 2>/dev/null; true"

echo "done. ВНИМАНИЕ: новой интеграции нужен рестарт HA Core (Settings -> System -> Restart),
затем добавить интеграцию MyNotify в Settings -> Devices & Services."
