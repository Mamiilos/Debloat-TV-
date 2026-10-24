#!/usr/bin/env bash
# What CI runs on every pull request (.github/workflows/check.yml). Run it before you push:
#
#   tools/check.sh           # everything
#   tools/check.sh android   # only the Google TV build (needs what build.sh needs)
#   tools/check.sh i18n      # only the translations (needs Python 3)
#
# One difference: here ./build.sh uses the newest SDK and JDK you have, while CI uses build-tools 34, android-34 and
# JDK 17, as the README says. A call newer than Android 34 can pass here and fail on the pull request.
set -euo pipefail
cd "$(dirname "$0")/.."
what="${1:-all}"
case "$what" in
  all|android|i18n) ;;
  *) echo "Usage: tools/check.sh [all|android|i18n]" >&2; exit 2 ;;
esac

if [ "$what" = all ] || [ "$what" = android ]; then
  ./build.sh
fi

if [ "$what" = all ] || [ "$what" = i18n ]; then
  python3 tools/translate.py --check
fi

echo "All checks passed."
