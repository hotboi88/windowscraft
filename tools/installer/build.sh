#!/bin/sh
# Builds the one-file installer, "Install WindowsCraft <version>.exe", with the given jar inside.
# usage: tools/installer/build.sh <windowscraft-…+mc1.21.1.jar> <output folder>
# (Git Bash on Windows, cargo with the x86_64-pc-windows-gnu target, like the native helpers.)
set -eu
here=$(cd "$(dirname "$0")" && pwd)
jar_dir=$(cd "$(dirname "$1")" && pwd)
jar_name=$(basename "$1")
out=$(cd "$2" && pwd)
version=$(printf '%s' "$jar_name" | sed -e 's/^windowscraft-//' -e 's/+.*//' -e 's/\.jar$//')

export WC_JAR="$(cygpath -m "$jar_dir/$jar_name")"
export WC_JAR_NAME="$jar_name"
# no build machine's folders inside the exe
export RUSTFLAGS="--remap-path-prefix=$(cygpath -m "$HOME")=~ --remap-path-prefix=$(cygpath -m "$here")=installer"
cd "$here"
cargo test --release --target x86_64-pc-windows-gnu --target-dir target-remap -q
cargo build --release --target x86_64-pc-windows-gnu --target-dir target-remap -q
cp target-remap/x86_64-pc-windows-gnu/release/wc-install.exe "$out/Install WindowsCraft $version.exe"
echo "$out/Install WindowsCraft $version.exe"
