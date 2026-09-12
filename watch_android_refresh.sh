#!/usr/bin/env bash

set -u
set -o pipefail

ADB_BIN="${ADB_BIN:-adb}"
REFRESH_LABEL="${REFRESH_LABEL:-새로고침}"
REMOTE_XML="/data/local/tmp/watch_android_refresh_$$.xml"
DEVICE_SERIAL="${ANDROID_SERIAL:-}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

die() {
  printf '오류: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"
}

cleanup() {
  if [ -n "${DEVICE_SERIAL:-}" ]; then
    "$ADB_BIN" -s "$DEVICE_SERIAL" shell rm -f "$REMOTE_XML" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v "$ADB_BIN" >/dev/null 2>&1 || die "adb를 찾을 수 없습니다. Android SDK platform-tools를 PATH에 추가하세요."
command -v "$PYTHON_BIN" >/dev/null 2>&1 || die "python3을 찾을 수 없습니다. UI XML 분석에 필요합니다."

select_device() {
  local device_list ready_devices device_count state

  if [ -n "$DEVICE_SERIAL" ]; then
    state=$("$ADB_BIN" -s "$DEVICE_SERIAL" get-state 2>/dev/null || true)
    [ "$state" = "device" ] || die "ANDROID_SERIAL=$DEVICE_SERIAL 디바이스가 준비되지 않았습니다."
    return
  fi

  device_list=$("$ADB_BIN" devices 2>/dev/null) || die "adb devices를 실행하지 못했습니다."
  ready_devices=$(printf '%s\n' "$device_list" | awk '$2 == "device" { print $1 }')
  device_count=$(printf '%s\n' "$ready_devices" | awk 'NF { count += 1 } END { print count + 0 }')

  case "$device_count" in
    0)
      die "준비된 Android 디바이스가 없습니다.\n$device_list"
      ;;
    1)
      DEVICE_SERIAL="$ready_devices"
      ;;
    *)
      die "여러 디바이스가 연결되어 있습니다. ANDROID_SERIAL을 지정하세요.\n$ready_devices"
      ;;
  esac
}

dump_ui() {
  "$ADB_BIN" -s "$DEVICE_SERIAL" shell uiautomator dump "$REMOTE_XML" >/dev/null 2>&1
}

find_refresh_point() {
  "$ADB_BIN" -s "$DEVICE_SERIAL" exec-out cat "$REMOTE_XML" |
    REFRESH_LABEL="$REFRESH_LABEL" "$PYTHON_BIN" -c '
import os
import re
import sys
import xml.etree.ElementTree as ET

label = os.environ.get("REFRESH_LABEL", "새로고침").strip()
bounds_pattern = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")

try:
    root = ET.parse(sys.stdin).getroot()
except (ET.ParseError, OSError) as error:
    print("UI XML을 분석하지 못했습니다: {}".format(error), file=sys.stderr)
    sys.exit(1)

matches = []

def visit(node, clickable_ancestor=None):
    enabled = node.attrib.get("enabled", "true") == "true"
    visible = node.attrib.get("visible-to-user", "true") != "false"
    clickable = enabled and visible and node.attrib.get("clickable") == "true"
    nearest_clickable = node if clickable else clickable_ancestor

    text = node.attrib.get("text", "").strip()
    content_description = node.attrib.get("content-desc", "").strip()
    if enabled and visible and (text == label or content_description == label):
        target = nearest_clickable
        if target is not None:
            bounds = target.attrib.get("bounds", "")
            match = bounds_pattern.fullmatch(bounds)
            if match:
                left, top, right, bottom = (int(value) for value in match.groups())
                if right > left and bottom > top:
                    matches.append(((left + right) // 2, (top + bottom) // 2, bounds))

    for child in node:
        visit(child, nearest_clickable)

visit(root)

unique_matches = []
for match in matches:
    if match not in unique_matches:
        unique_matches.append(match)

if len(unique_matches) == 0:
    print("'{}' 버튼을 찾지 못했습니다.".format(label), file=sys.stderr)
    sys.exit(1)
if len(unique_matches) > 1:
    descriptions = ", ".join("({}, {}) bounds={}".format(x, y, bounds) for x, y, bounds in unique_matches)
    print("'{}' 버튼 후보가 여러 개입니다: {}".format(label, descriptions), file=sys.stderr)
    sys.exit(1)

x, y, _ = unique_matches[0]
print("{} {}".format(x, y))
'
}

select_device
log "대상 디바이스: $DEVICE_SERIAL"

dump_ui || die "현재 화면의 UI 계층을 가져오지 못했습니다."
coordinates=$(find_refresh_point) || die "새로고침 버튼을 찾지 못했습니다. 현재 화면을 확인하세요."

refresh_x=${coordinates%% *}
refresh_y=${coordinates#* }
if ! [[ "$refresh_x" =~ ^[0-9]+$ && "$refresh_y" =~ ^[0-9]+$ ]]; then
  die "새로고침 버튼 좌표가 올바르지 않습니다: $coordinates"
fi

log "새로고침 버튼 좌표: ($refresh_x, $refresh_y)"
log "2~5초 랜덤 간격 탭을 시작합니다. 종료하려면 Ctrl-C를 누르세요."

while :; do
  if "$ADB_BIN" -s "$DEVICE_SERIAL" shell input tap "$refresh_x" "$refresh_y" >/dev/null 2>&1; then
    log "새로고침 탭 완료"
  else
    printf '[%s] 경고: 디바이스에 탭을 전달하지 못했습니다. 다음 랜덤 간격 후 재시도합니다.\n' "$(date '+%H:%M:%S')" >&2
  fi
  delay_seconds=$((RANDOM % 4 + 2))
  log "${delay_seconds}초 후 다음 탭을 시도합니다."
  sleep "$delay_seconds"
done
