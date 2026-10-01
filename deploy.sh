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

# Grab the exact commit hash we just pushed
COMMIT_SHA=$(git rev-parse HEAD)
echo "Tracking commit: $COMMIT_SHA"

echo "==> 3. Waiting for GitHub to register workflow for this commit..."
RUN_ID=""
for i in $(seq 1 20); do
    # Only match runs triggered for THIS specific commit
    RUN_ID=$(gh run list --commit "$COMMIT_SHA" --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null || true)
    
    if [ -n "$RUN_ID" ]; then
        echo "Found new run ID: $RUN_ID"
        break
    fi
    echo "Waiting for runner initialization... ($i/20)"
    sleep 2
done

if [ -z "$RUN_ID" ]; then
    echo "Error: GitHub did not register a workflow for commit $COMMIT_SHA within 40 seconds."
    exit 1
fi

echo "==> 4. Monitoring build (Run ID: $RUN_ID)..."
gh run watch "$RUN_ID"

echo "==> 5. Downloading compiled APK..."
DEST_DIR="/sdcard/Download"
mkdir -p "$DEST_DIR"

# 1. Create a guaranteed empty temporary directory
TMP_DIR=$(mktemp -d)

# 2. Extract cleanly with zero collision
gh run download "$RUN_ID" -n app-debug -D "$TMP_DIR"

# 3. Locate and overwrite the destination APK
APK_FILE=$(find "$TMP_DIR" -type f -name "*.apk" | head -n 1)
if [ -n "$APK_FILE" ]; then
    cp -f "$APK_FILE" "$DEST_DIR/app-debug.apk"
    rm -rf "$TMP_DIR"
    echo "Successfully saved to: $DEST_DIR/app-debug.apk"
else
    rm -rf "$TMP_DIR"
    echo "Error: No APK found inside the downloaded artifact."
    exit 1
fi