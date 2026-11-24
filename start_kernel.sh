#!/usr/bin/env bash
# 仅启动 openTCS 内核，不启动 ControlCenter 和 OperationsDesk。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
KERNEL_DIR="$SCRIPT_DIR/opentcs/build/install/opentcs/opentcs-kernel"

if [[ ! -f "$KERNEL_DIR/startKernel.sh" ]]; then
    echo "错误：找不到内核启动脚本 $KERNEL_DIR/startKernel.sh" >&2
    exit 1
fi

echo '正在启动 openTCS 内核……'
echo '请保持此终端打开，按 Ctrl+C 停止内核。'
cd -- "$KERNEL_DIR"
exec bash ./startKernel.sh
