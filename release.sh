#!/bin/bash
set -e

# Usage: ./release.sh [version_name]
# Example: ./release.sh 1.1

if [ -z "$1" ]; then
    echo "Usage: $0 <version_name>"
    exit 1
fi

NEW_VERSION="$1"
GRADLE_FILE="app/build.gradle.kts"

echo "🚀 Preparing release $NEW_VERSION..."

# 1. Update versionName and increment versionCode
CURRENT_CODE=$(grep "versionCode =" $GRADLE_FILE | awk '{print $3}')
NEW_CODE=$((CURRENT_CODE + 1))

echo "Updating version: $NEW_VERSION (Code: $NEW_CODE)"

# Use sed to update the values
# Note: macOS sed requires an empty string for -i or it treats the next arg as extension
sed -i '' "s/versionCode = .*/versionCode = $NEW_CODE/" $GRADLE_FILE
sed -i '' "s/versionName = .*/versionName = \"$NEW_VERSION\"/" $GRADLE_FILE

# 2. Commit the version bump
git add $GRADLE_FILE
git commit -m "Bump version to $NEW_VERSION ($NEW_CODE)"
git tag -a "v$NEW_VERSION" -m "Release v$NEW_VERSION"

# 3. Build APKs
echo "📦 Building APKs..."
./gradlew clean assembleRelease

# 4. Locate APKs
UNIVERSAL_APK="app/build/outputs/apk/release/app-universal-release.apk"
ARM64_APK="app/build/outputs/apk/release/app-arm64-v8a-release.apk"

if [ ! -f "$UNIVERSAL_APK" ]; then
    echo "❌ Error: Universal APK not found at $UNIVERSAL_APK"
    exit 1
fi

if [ ! -f "$ARM64_APK" ]; then
    echo "❌ Error: arm64-v8a APK not found at $ARM64_APK"
    exit 1
fi

# 5. GitHub Release
echo "📤 Uploading to GitHub..."

if command -v gh &> /dev/null; then
    gh release create "v$NEW_VERSION" \
        --title "Release v$NEW_VERSION" \
        --notes "Automated release of v$NEW_VERSION" \
        "$UNIVERSAL_APK#RemoCard-Full-$NEW_VERSION.apk" \
        "$ARM64_APK#RemoCard-arm64-$NEW_VERSION.apk"
    
    echo "✅ Release v$NEW_VERSION published successfully!"
else
    echo "⚠️  'gh' CLI not found. Skipping GitHub upload."
    echo "Please install it (brew install gh) and login (gh auth login) to automate uploads."
    echo "Manual upload path: $UNIVERSAL_APK and $ARM64_APK"
fi

# Push changes and tag
git push origin master --tags

echo "🎉 Done! Version $NEW_VERSION is out."
