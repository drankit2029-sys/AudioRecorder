#!/bin/sh
set -e

COMMIT_MSG="${1:-Update build}"

echo "==> 1. Staging and committing..."
git add .
if git diff-index --quiet HEAD --; then
    echo "No changes to commit. Pushing existing commits..."
else
    git commit -m "$COMMIT_MSG"
fi

echo "==> 2. Pushing to GitHub..."
git push origin main

echo "==> 3. Waiting for GitHub to register workflow run..."
RUN_ID=""
for i in $(seq 1 15); do
    RUN_ID=$(gh run list --branch main --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null || true)
    if [ -n "$RUN_ID" ]; then
        echo "Found run ID: $RUN_ID"
        break
    fi
    sleep 2
done

if [ -z "$RUN_ID" ]; then
    echo "Error: No GitHub Actions run detected. Check repo Actions settings."
    exit 1
fi

echo "==> 4. Monitoring build (Run ID: $RUN_ID)..."
gh run watch "$RUN_ID"

echo "==> 5. Downloading compiled APK..."
DEST_DIR="/sdcard/Download"
mkdir -p "$DEST_DIR"
gh run download "$RUN_ID" -n app-debug -D "$DEST_DIR" --clobber

# Locate the downloaded APK
APK_PATH=$(find "$DEST_DIR" -maxdepth 2 -name "*.apk" -type f | head -n 1)

if [ -z "$APK_PATH" ]; then
    echo "Error: Could not locate downloaded APK in $DEST_DIR"
    exit 1
fi

echo "Found APK at: $APK_PATH"

echo "==> 6. Installing APK..."

# Method A: Silent background install via ADB (if connected)
if command -v adb >/dev/null 2>&1 && adb get-state 1>/dev/null 2>&1; then
    echo "ADB device detected. Performing silent background install..."
    adb install -r "$APK_PATH"
    echo "Successfully installed via ADB!"

# Method B: Silent install via Root (if rooted)
elif command -v su >/dev/null 2>&1; then
    echo "Root detected. Performing silent background install..."
    su -c "pm install -r '$APK_PATH'"
    echo "Successfully installed via root!"

# Method C: Interactive system installer prompt via Termux
elif command -v termux-open >/dev/null 2>&1; then
    echo "Triggering Android installer prompt..."
    termux-open "$APK_PATH"

# Method D: System intent fallback (PRoot / Alpine)
elif command -v am >/dev/null 2>&1; then
    echo "Triggering package manager intent..."
    am start -a android.intent.action.VIEW \
             -d "content://com.android.externalstorage.documents/document/primary:Download/$(basename "$APK_PATH")" \
             -t "application/vnd.android.package-archive"
else
    echo "APK downloaded to: $APK_PATH"
    echo "Open your phone's 'Downloads' drawer and tap the APK to install."
fi