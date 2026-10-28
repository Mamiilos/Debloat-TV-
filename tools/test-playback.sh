#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/playback-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/playback-tests \
  app/src/io/freemytv/PlaybackEvidence.java app/src/io/freemytv/TraktDeviceFlow.java \
  tests/PlaybackEvidenceTest.java tests/TraktDeviceFlowTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/playback-tests io.freemytv.PlaybackEvidenceTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/playback-tests io.freemytv.TraktDeviceFlowTest
