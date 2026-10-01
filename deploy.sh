#!/bin/sh
set -e

COMMIT_MSG="${1:-Update build}"

echo "==> 1. Staging and committing changes..."
git add .
if git diff-index --quiet HEAD --; then
    echo "No changes to commit. Pushing existing commits..."
else
    git commit -m "$COMMIT_MSG"
fi

echo "==> 2. Pushing to GitHub..."
git push origin main

echo "==> 3. Monitoring cloud build in real time..."
# Sleep 3 seconds so GitHub registers the webhook run before watching
sleep 3
gh run watch

echo "==> 4. Downloading compiled APK to phone storage..."
DEST_DIR="/sdcard/Download"
mkdir -p "$DEST_DIR"
gh run download -n app-debug -D "$DEST_DIR"

echo "==> 5. Launching installer..."
APK_PATH="$DEST_DIR/app-debug.apk"

# Method A: Termux environment
if command -v termux-open >/dev/null 2>&1; then
    termux-open "$APK_PATH"
# Method B: Root/system activity manager fallback
elif command -v am >/dev/null 2>&1; then
    am start -a android.intent.action.VIEW -d "file://$APK_PATH" -t "application/vnd.android.package-archive"
else
    echo "APK saved to: $APK_PATH"
    echo "Open your phone's 'Downloads' app and tap 'app-debug.apk' to install."
fi