#!/bin/bash
# CLI to run Non-Regression tests for HarnessDroid

echo "============================================="
echo "Running Non-Regression Tests (Unit Tests)..."
echo "============================================="

./gradlew testDebugUnitTest --no-daemon

if [ $? -eq 0 ]; then
    echo "✅ Non-Regression tests passed successfully."
else
    echo "❌ Non-Regression tests failed! Check logs above."
    # Extract the test report path from gradle output or just show the html
    echo "Test report is at: app/build/reports/tests/testDebugUnitTest/index.html"
    exit 1
fi
