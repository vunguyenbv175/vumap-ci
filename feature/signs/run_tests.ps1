$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
if (Test-Path build) { Remove-Item -Recurse -Force build }
New-Item -ItemType Directory build/classes | Out-Null
javac -encoding UTF-8 -d build/classes `
  src/main/java/vn/vumap/signs/*.java `
  src/test/java/vn/vumap/signs/*.java
java -cp build/classes vn.vumap.signs.RunAllTests
python tools/validate_fixture.py fixtures/synthetic_vn_signs_p1.json
python tools/pack/run_pack_tests.py
