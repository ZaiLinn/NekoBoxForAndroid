#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

# Unauthenticated API calls are rate limited (shared CI runners hit it), which returned an
# empty tag and a 404 download: use GITHUB_TOKEN when set, and fall back to the tag that
# github.com/<repo>/releases/latest redirects to.
get_latest_release() {
  local tag
  tag=$(curl --silent ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
    "https://api.github.com/repos/$1/releases/latest" |
    grep '"tag_name":' |
    sed -E 's/.*"([^"]+)".*/\1/')
  if [ -z "$tag" ]; then
    tag=$(curl --silent --head "https://github.com/$1/releases/latest" |
      grep -i '^location:' | sed -E 's|.*/tag/([^[:space:]]+).*|\1|' | tr -d '\r')
  fi
  if [ -z "$tag" ]; then
    echo "cannot find the latest release of $1" >&2
    exit 1
  fi
  echo "$tag"
}

####
VERSION_GEOIP=`get_latest_release "SagerNet/sing-geoip"`
echo VERSION_GEOIP=$VERSION_GEOIP
echo -n $VERSION_GEOIP > geoip.version.txt
curl -fLSsO https://github.com/SagerNet/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db
xz -9 geoip.db

####
VERSION_GEOSITE=`get_latest_release "SagerNet/sing-geosite"`
echo VERSION_GEOSITE=$VERSION_GEOSITE
echo -n $VERSION_GEOSITE > geosite.version.txt
curl -fLSsO https://github.com/SagerNet/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db
xz -9 geosite.db
