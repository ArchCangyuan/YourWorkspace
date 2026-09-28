#!/usr/bin/env bash
# Provides the IronRDP web client (MIT OR Apache-2.0) used by the built-in
# remote desktop and places it next to rdp.html in the app assets.
#
# The web component comes from npm (verified). The RDP backend is built from
# a pinned IronRDP commit: the latest npm release (0.7.0, May 2026) predates
# upstream's file-upload fixes (release stale clipboard locks before a new
# file list, recover interrupted pastes, supersede stuck uploads), without
# which pasting phone files into Windows could leave rdpclip and Explorer hung.
# Needs git, a Rust toolchain (rustup) and npm.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DESTINATIONS=("$ROOT/android/app/src/main/assets/rdp")

WEB_COMPONENT="@devolutions/iron-remote-desktop@0.11.0"
WEB_COMPONENT_SHA256="22359dfb201017ebf7f25c2e496dbbfd1668bcad1a34d0dabfd05a968e1aa50f"

IRONRDP_REPO="https://github.com/Devolutions/IronRDP.git"
IRONRDP_COMMIT="22006ce1f9dae8a052ae8b6131fb5f7f2fce663e"
# Kept between runs (CI caches it) so Cargo can reuse the build.
IRONRDP_DIR="${IRONRDP_BUILD_DIR:-$ROOT/.ironrdp-build}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

install_asset() {
  local source="$1" file="$2"
  for destination in "${DESTINATIONS[@]}"; do
    mkdir -p "$destination"
    cp "$source" "$destination/$file"
  done
}

fetch() {
  local spec="$1" file="$2" expected="$3"
  mkdir -p "$WORK/$file"
  (cd "$WORK/$file" && npm pack "$spec" --silent >/dev/null && tar xzf ./*.tgz)
  local actual
  actual="$(sha256sum "$WORK/$file/package/$file" | cut -d' ' -f1)"
  if [[ "$actual" != "$expected" ]]; then
    echo "Checksum mismatch for $spec ($file): $actual" >&2
    exit 1
  fi
  install_asset "$WORK/$file/package/$file" "$file"
}

build_rdp_backend() {
  if [[ ! -d "$IRONRDP_DIR/.git" ]]; then
    rm -rf "$IRONRDP_DIR"
    git init -q "$IRONRDP_DIR"
    git -C "$IRONRDP_DIR" remote add origin "$IRONRDP_REPO"
  fi
  if [[ "$(git -C "$IRONRDP_DIR" rev-parse HEAD 2>/dev/null)" != "$IRONRDP_COMMIT" ]]; then
    git -C "$IRONRDP_DIR" fetch -q --depth 1 origin "$IRONRDP_COMMIT"
    git -C "$IRONRDP_DIR" checkout -q --force "$IRONRDP_COMMIT"
  fi
  git -C "$IRONRDP_DIR" clean -q -fdx -e target -e node_modules

  local web="$IRONRDP_DIR/crates/ironrdp-web"
  (cd "$IRONRDP_DIR" && rustup show active-toolchain >/dev/null && rustup target add wasm32-unknown-unknown >/dev/null)

  # wasm-bindgen's CLI must match the library version in Cargo.lock.
  local bindgen_version
  bindgen_version="$(awk '/^name = "wasm-bindgen"$/ { getline; gsub(/[^0-9.]/, ""); print; exit }' "$IRONRDP_DIR/Cargo.lock")"
  if [[ "$(wasm-bindgen --version 2>/dev/null | awk '{print $2}')" != "$bindgen_version" ]]; then
    cargo install --quiet --locked wasm-bindgen-cli --version "$bindgen_version"
  fi

  # Same flags as IronRDP's `cargo xtask web build`.
  (cd "$web" && RUSTFLAGS='-Ctarget-feature=+simd128,+bulk-memory --cfg getrandom_backend="wasm_js" -Copt-level=s -Ccodegen-units=1 -Cllvm-args=-enable-dfa-jump-thread' \
    cargo build --quiet --release --target wasm32-unknown-unknown)
  rm -rf "$web/pkg"
  wasm-bindgen --target web --out-dir "$web/pkg" \
    "$IRONRDP_DIR/target/wasm32-unknown-unknown/release/ironrdp_web.wasm"

  # As xtask does: Vite inlines the module only through a `?url` import.
  local glue="$web/pkg/ironrdp_web.js"
  grep -q "new URL('ironrdp_web_bg.wasm', import.meta.url)" "$glue"
  { echo "import wasmUrl from './ironrdp_web_bg.wasm?url';"; echo;
    sed "s|new URL('ironrdp_web_bg.wasm', import.meta.url)|wasmUrl|" "$glue"; } > "$glue.tmp"
  mv "$glue.tmp" "$glue"

  local frontend="$IRONRDP_DIR/web-client/iron-remote-desktop-rdp"
  (cd "$frontend" && npm ci --silent --no-audit --no-fund && npx --no-install vite build --logLevel warn)
  install_asset "$frontend/dist/iron-remote-desktop-rdp.js" iron-remote-desktop-rdp.js
}

fetch "$WEB_COMPONENT" iron-remote-desktop.js "$WEB_COMPONENT_SHA256"
build_rdp_backend
echo "IronRDP web client ready (backend from IronRDP $IRONRDP_COMMIT)."
