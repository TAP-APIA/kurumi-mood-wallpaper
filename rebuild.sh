#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
mkdir -p app/src/main/res/raw
cp assets/videos/mood_*.mp4 app/src/main/res/raw/
./gradlew :app:assembleDebug
