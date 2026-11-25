c#!/usr/bin/env bash
# User-local Android build setup. No sudo, no Android Studio.
# Installs Temurin JDK 17, Android cmdline-tools/sdkmanager, the SDK packages
# this repo compiles against, and writes env vars plus local.properties.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

JAVA_HOME_DIR="${JAVA_HOME_DIR:-$HOME/jdks/temurin-17}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ENV_FILE="${ENV_FILE:-$HOME/.android-dev-env}"
CMDLINE_TOOLS_URL="${CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip}"
JDK_URL="${JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk}"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required tool: $1" >&2
    exit 1
  fi
}

need curl
need tar
need unzip
need awk

if [[ "$(uname -m)" != "x86_64" ]]; then
  echo "This script downloads the Linux x64 JDK and command-line tools. Detected: $(uname -m)" >&2
  exit 1
fi

mkdir -p "$HOME/jdks" "$ANDROID_HOME/cmdline-tools" "$REPO_ROOT"

if [[ ! -x "$JAVA_HOME_DIR/bin/java" ]]; then
  echo "Downloading Temurin JDK 17..."
  tmp_jdk=$(mktemp -d)
  curl -fL --retry 3 -o "$tmp_jdk/jdk.tar.gz" "$JDK_URL"
  tar -xzf "$tmp_jdk/jdk.tar.gz" -C "$tmp_jdk"
  extracted=$(find "$tmp_jdk" -mindepth 1 -maxdepth 1 -type d -name 'jdk-*' | head -1)
  if [[ -z "$extracted" ]]; then
    echo "JDK archive did not contain a jdk-* directory" >&2
    exit 1
  fi
  rm -rf "$JAVA_HOME_DIR"
  mv "$extracted" "$JAVA_HOME_DIR"
  rm -rf "$tmp_jdk"
else
  echo "JDK already present at $JAVA_HOME_DIR"
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
  echo "Downloading Android command-line tools..."
  tmp_sdk=$(mktemp -d)
  curl -fL --retry 3 -o "$tmp_sdk/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  unzip -q "$tmp_sdk/cmdline-tools.zip" -d "$tmp_sdk"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp_sdk/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp_sdk"
else
  echo "sdkmanager already present at $SDKMANAGER"
fi

export JAVA_HOME="$JAVA_HOME_DIR"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo "Using Java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

echo "Accepting Android SDK licenses..."
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
set -o pipefail

echo "Installing SDK packages (platforms 35 and 36, build-tools 35.0.0)..."
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install \
  "platforms;android-35" \
  "platforms;android-36" \
  "build-tools;35.0.0"

cat > "$ENV_FILE" <<EOF
export JAVA_HOME="$JAVA_HOME_DIR"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
EOF

marker="# android-dev-env"
if [[ -f "$HOME/.bashrc" ]] && grep -qF "$marker" "$HOME/.bashrc"; then
  echo "PATH hook already in $HOME/.bashrc"
else
  printf '\n%s\n[ -f "%s" ] && . "%s"\n' "$marker" "$ENV_FILE" "$ENV_FILE" >> "$HOME/.bashrc"
  echo "Added PATH hook to $HOME/.bashrc"
fi

# local.properties is gitignored. Escape backslashes for the properties format.
sdk_dir_escaped=${ANDROID_HOME//\\/\\\\}
printf 'sdk.dir=%s\n' "$sdk_dir_escaped" > "$REPO_ROOT/local.properties"
chmod +x "$REPO_ROOT/gradlew"

echo
echo "Done."
echo "  JAVA_HOME=$JAVA_HOME_DIR"
echo "  ANDROID_HOME=$ANDROID_HOME"
echo "  env file=$ENV_FILE"
echo "  sdk.dir written to $REPO_ROOT/local.properties"
echo
echo "This script cannot export variables into the shell that launched it."
echo "Open a new terminal, or run:"
echo "  source \"$ENV_FILE\""
echo "Then build and install:"
echo "  cd \"$REPO_ROOT\""
echo "  ./gradlew :app:assembleDebug"
echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"
