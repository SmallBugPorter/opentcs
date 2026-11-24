#!/usr/bin/env bash
# 独立启动 openTCS 模型编辑器，沿用控制台的 Metal 风格及 2 倍缩放。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
EDITOR_DIR="$SCRIPT_DIR/opentcs/build/install/opentcs/opentcs-modeleditor"
OPENTCS_UI_STYLE="${OPENTCS_UI_STYLE:-metal}"
OPENTCS_UI_SCALE="${OPENTCS_UI_SCALE:-2}"

case "$OPENTCS_UI_STYLE" in
    metal|system) ;;
    *) echo '错误：OPENTCS_UI_STYLE 必须为 metal 或 system。' >&2; exit 1 ;;
esac
if [[ ! "$OPENTCS_UI_SCALE" =~ ^[1-4]$ ]]; then
    echo '错误：OPENTCS_UI_SCALE 必须为 1 到 4 的整数。' >&2
    exit 1
fi

if [[ ! -f "$EDITOR_DIR/startModelEditor.sh" ]]; then
    echo "错误：找不到启动脚本 $EDITOR_DIR/startModelEditor.sh" >&2
    exit 1
fi

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dsun.java2d.uiScale=$OPENTCS_UI_SCALE"
if [[ "$OPENTCS_UI_STYLE" == metal ]]; then
    JAVA_TOOL_OPTIONS+=" -Dswing.systemlaf=javax.swing.plaf.metal.MetalLookAndFeel"
fi

echo "启动模型编辑器；界面样式：$OPENTCS_UI_STYLE；界面缩放：$OPENTCS_UI_SCALE"
echo '请保持此终端打开，关闭编辑器窗口或按 Ctrl+C 退出。'
cd -- "$EDITOR_DIR"
exec bash ./startModelEditor.sh
