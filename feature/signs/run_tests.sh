#!/bin/sh
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac -encoding UTF-8 -d build/classes \
  src/main/java/vn/vumap/signs/*.java \
  src/test/java/vn/vumap/signs/*.java
java -cp build/classes vn.vumap.signs.RunAllTests
python3 tools/validate_fixture.py fixtures/synthetic_vn_signs_p1.json 2>/dev/null \
  || python tools/validate_fixture.py fixtures/synthetic_vn_signs_p1.json
python3 tools/pack/run_pack_tests.py 2>/dev/null \
  || python tools/pack/run_pack_tests.py
