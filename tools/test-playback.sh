#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/playback-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/playback-tests \
  app/src/io/freemytv/PlaybackEvidence.java app/src/io/freemytv/TraktDeviceFlow.java \
  app/src/io/freemytv/WatchTitle.java app/src/io/freemytv/RecommendationMix.java app/src/io/freemytv/ImageSizing.java \
  tests/PlaybackEvidenceTest.java tests/TraktDeviceFlowTest.java tests/WatchTitleTest.java tests/RecommendationMixTest.java tests/ImageSizingTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/playback-tests io.freemytv.PlaybackEvidenceTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/playback-tests io.freemytv.TraktDeviceFlowTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/playback-tests io.freemytv.WatchTitleTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/playback-tests io.freemytv.RecommendationMixTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/playback-tests io.freemytv.ImageSizingTest
