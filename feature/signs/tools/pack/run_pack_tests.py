"""Single entry point: run every P1 pack pipeline host test (stdlib only)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def main() -> int:
    loader = unittest.TestLoader()
    suite = loader.discover(str(HERE), pattern="test_*.py")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    total = result.testsRun
    if result.wasSuccessful():
        print(f"RESULT: ALL {total} PACK TESTS PASSED")
        return 0
    failed = len(result.failures) + len(result.errors)
    print(f"RESULT: PACK TESTS FAILED ({failed}/{total})")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
