#!/bin/sh
set -eu
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/home/elder/Android/Sdk}"
JDK="${JDK_HOME:-/usr/lib/jvm/java-8-temurin}"
mkdir -p build/classes dist
"$JDK/bin/javac" -encoding UTF-8 -cp "$SDK/platforms/android-34/android.jar" -d build/classes src/*.java
"$JDK/bin/java" -cp build/classes DriftPhysicsCheck
"$SDK/build-tools/34.0.0/d8" --min-api 34 --lib "$SDK/platforms/android-34/android.jar" --output dist build/classes/*.class
python3 -m py_compile control.py
python3 check_control.py
