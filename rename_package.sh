#!/bin/bash
set -e

OLD_PKG="com\.tree4five\.harness"
NEW_PKG="com.ai.harnessdroid"
OLD_DIR="com/tree4five/harness"
NEW_DIR="com/ai/harnessdroid"

# 1. Update build.gradle.kts
sed -i 's/namespace = "com.tree4five.harness"/namespace = "com.ai.harnessdroid"/g' app/build.gradle.kts
sed -i 's/applicationId = "com.tree4five.harness"/applicationId = "com.ai.harnessdroid"/g' app/build.gradle.kts

# 2. Update all file contents
find app/src -type f -name "*.kt" -o -name "*.xml" -o -name "*.aidl" | xargs sed -i "s/$OLD_PKG/$NEW_PKG/g"

# 3. Create new directories and move files
# Java/Kotlin
mkdir -p app/src/main/java/$NEW_DIR
mv app/src/main/java/$OLD_DIR/* app/src/main/java/$NEW_DIR/
rm -rf app/src/main/java/com/tree4five/harness
# Check if com/tree4five is empty (excluding gguf) and remove if needed

mkdir -p app/src/androidTest/java/$NEW_DIR
mv app/src/androidTest/java/$OLD_DIR/* app/src/androidTest/java/$NEW_DIR/
rm -rf app/src/androidTest/java/com/tree4five/harness

mkdir -p app/src/test/java/$NEW_DIR
mv app/src/test/java/$OLD_DIR/* app/src/test/java/$NEW_DIR/
rm -rf app/src/test/java/com/tree4five/harness

# AIDL
mkdir -p app/src/main/aidl/$NEW_DIR
mv app/src/main/aidl/$OLD_DIR/* app/src/main/aidl/$NEW_DIR/
rm -rf app/src/main/aidl/com/tree4five/harness

echo "Package rename completed."
