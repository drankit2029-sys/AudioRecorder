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
    # Fetch the ID of the latest run triggered on main
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
gh run download "$RUN_ID" -n app-debug -D "$DEST_DIR"

echo "==> 6. Launching installer..."
APK_PATH="$DEST_DIR/app-debug.apk"
if command -v termux-open >/dev/null 2>&1; then
    termux-open "$APK_PATH"
else
    echo "APK downloaded to: $APK_PATH"
fi