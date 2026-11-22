#!/usr/bin/env bash
# Build the debug or release APK.
# Usage: ./scripts/build.sh debug|release
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
ENV_FILE="${ENV_FILE:-$HOME/.android-dev-env}"

usage() {
  echo "Usage: $0 debug|release" >&2
  exit 2
}

if [[ $# -ne 1 ]]; then
  usage
fi

variant=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
case "$variant" in
  debug) gradle_task=":app:assembleDebug" ;;
  release) gradle_task=":app:assembleRelease" ;;
  *) usage ;;
esac

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/jdks/temurin-17/bin/java" ]]; then
  export JAVA_HOME="$HOME/jdks/temurin-17"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v java >/dev/null 2>&1; then
  echo "error: java is not on PATH. Run ./scripts/setup-android-sdk.sh or: source \"$ENV_FILE\"" >&2
  exit 1
fi

cd "$REPO_ROOT"
./gradlew "$gradle_task"

echo
echo "Build finished ($variant)."
if [[ "$variant" == "debug" ]]; then
  echo "APK: $REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
else
  echo "APK directory: $REPO_ROOT/app/build/outputs/apk/release/"
  echo "This project has no release signing config, so Gradle emits an unsigned APK."
  echo "adb cannot install that file. Use debug for device installs, or add a signingConfig."
fi
