#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
PATCH_DIR="$(pwd)/buildScript/lib/core/patches"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/sing-box.git
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
# local patches, skipped if already applied
for patch in "$PATCH_DIR"/sing-box-*.patch; do
  [ -f "$patch" ] || continue
  if git apply --reverse --check "$patch" 2>/dev/null; then
    echo ">> already applied: $(basename "$patch")"
  else
    git apply "$patch"
    echo ">> applied: $(basename "$patch")"
  fi
done
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
