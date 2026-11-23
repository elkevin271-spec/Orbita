#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
javac -encoding UTF-8 -d build/test-classes app/src/main/java/hn/orbita/SpatialEngine.java app/src/main/java/hn/orbita/DemoKnocks.java tests/AudioEngineTest.java
java -cp build/test-classes AudioEngineTest
