#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: build-native.sh \
  --target <rust-target> \
  --build-tool <cargo|cargo-xwin> \
  --manifest-path <path/to/yffi/Cargo.toml> \
  --target-dir <cargo-target-dir> \
  --built-artifact <path/to/built/library> \
  --destination <resource/library/path>
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

target=""
build_tool=""
manifest_path=""
target_dir=""
built_artifact=""
destination=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      target="${2:-}"
      shift 2
      ;;
    --build-tool)
      build_tool="${2:-}"
      shift 2
      ;;
    --manifest-path)
      manifest_path="${2:-}"
      shift 2
      ;;
    --target-dir)
      target_dir="${2:-}"
      shift 2
      ;;
    --built-artifact)
      built_artifact="${2:-}"
      shift 2
      ;;
    --destination)
      destination="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$target" || -z "$build_tool" || -z "$manifest_path" || -z "$target_dir" || -z "$built_artifact" || -z "$destination" ]]; then
  echo "Missing required argument." >&2
  usage >&2
  exit 1
fi

if [[ ! -f "$manifest_path" ]]; then
  echo "Cargo manifest does not exist: $manifest_path" >&2
  exit 1
fi

require_command cargo
require_command rustup

echo ">> rustup target add $target"
rustup target add "$target"

mkdir -p "$target_dir"

if [[ "$target" == "aarch64-unknown-linux-gnu" ]]; then
  require_command aarch64-linux-gnu-gcc
  export CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc
  export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc
fi

case "$build_tool" in
  cargo)
    echo ">> cargo build --manifest-path $manifest_path --release --target $target --target-dir $target_dir"
    cargo build \
      --manifest-path "$manifest_path" \
      --release \
      --target "$target" \
      --target-dir "$target_dir"
    ;;
  cargo-xwin)
    echo ">> cargo xwin build --manifest-path $manifest_path --release --target $target --target-dir $target_dir"
    cargo xwin build \
      --manifest-path "$manifest_path" \
      --release \
      --target "$target" \
      --target-dir "$target_dir"
    ;;
  *)
    echo "Unknown build tool: $build_tool" >&2
    exit 1
    ;;
esac

if [[ ! -f "$built_artifact" ]]; then
  echo "Expected native library was not produced: $built_artifact" >&2
  exit 1
fi

mkdir -p "$(dirname "$destination")"
cp -f "$built_artifact" "$destination"
echo "Copied $destination"
