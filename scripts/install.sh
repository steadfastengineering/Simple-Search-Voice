#!/usr/bin/env bash
# Install the already-built debug or release APK onto every adb-accessible device.
# Usage: ./scripts/install.sh debug|release
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
PACKAGE="de.tobiasbielefeld.searchbar"

usage() {
  echo "Usage: $0 debug|release" >&2
  exit 2
}

if [[ $# -ne 1 ]]; then
  usage
fi

variant=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
case "$variant" in
  debug)
    apk="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    apk="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
    unsigned="$REPO_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
    if [[ ! -f "$apk" && -f "$unsigned" ]]; then
      echo "error: the release APK is unsigned ($unsigned)." >&2
      echo "adb cannot install an unsigned release build." >&2
      echo "Install the debug build instead: $0 debug" >&2
      echo "Or add a release signingConfig and rebuild." >&2
      exit 1
    fi
    ;;
  *) usage ;;
esac

if [[ ! -f "$apk" ]]; then
  echo "error: APK not found: $apk" >&2
  echo "Build it first: $SCRIPT_DIR/build.sh $variant" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb is not on PATH, so this script cannot access a device." >&2
  echo "Install Android platform-tools and confirm 'adb version' works." >&2
  exit 1
fi

if ! adb_output=$(adb devices 2>&1); then
  echo "error: adb cannot access devices." >&2
  echo "$adb_output" >&2
  echo "Start the adb server, plug the device in, and enable USB debugging." >&2
  exit 1
fi

mapfile -t rows < <(printf '%s\n' "$adb_output" | awk 'NR > 1 && NF >= 2 { print $1, $2 }')

if [[ ${#rows[@]} -eq 0 ]]; then
  echo "error: adb cannot access a device. No devices are attached." >&2
  echo "Plug in a device, enable USB debugging, and accept the RSA prompt." >&2
  echo "Then check: adb devices -l" >&2
  exit 1
fi

serials=()
blocked=()
for row in "${rows[@]}"; do
  serial=${row%% *}
  state=${row##* }
  if [[ "$state" == "device" ]]; then
    serials+=("$serial")
  else
    blocked+=("$serial ($state)")
  fi
done

if [[ ${#blocked[@]} -gt 0 ]]; then
  echo "error: adb can see a device but cannot access it:" >&2
  printf '  %s\n' "${blocked[@]}" >&2
  echo "Unlock the device and accept the USB debugging authorization prompt." >&2
  echo "If the state is 'offline' or 'no permissions', replug the cable and run: adb devices -l" >&2
  exit 1
fi

echo "Installing $apk"
for serial in "${serials[@]}"; do
  echo "-> $serial"
  if ! adb -s "$serial" install -r "$apk"; then
    echo "error: install failed on $serial." >&2
    echo "If a Play or F-Droid build is already installed, signatures will not match. Uninstall it, then retry:" >&2
    echo "  adb -s $serial uninstall $PACKAGE" >&2
    exit 1
  fi
done

echo "Installed on ${#serials[@]} device(s)."
