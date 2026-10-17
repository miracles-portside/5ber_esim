#!/usr/bin/env bash
# Builds iccbr.jar — a dex-in-jar run by app_process64 as shell on the phone
# (see IccBr.java). Needs javac and d8 on PATH.
set -e
cd "$(dirname "$0")"

rm -rf build
mkdir -p build/classes build/dex

# The stubs exist only for compilation — at runtime app_process loads the platform's
# real android.os.Binder / IBinder / ServiceManager from the boot classpath.
javac -source 8 -target 8 -nowarn -d build/classes stubs/android/os/*.java IccBr.java

d8 --release --min-api 24 --output build/dex $(find build/classes -name '*.class')

( cd build/dex && jar cf ../iccbr.jar classes.dex )

echo "built $(pwd)/build/iccbr.jar"
echo "deploy: adb push build/iccbr.jar /data/local/tmp/iccbr.jar"
