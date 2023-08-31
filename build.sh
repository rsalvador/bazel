#!/bin/bash
set -v

bazel build --verbose_failures --sandbox_debug //src:bazel
bazel build //src:java_tools_zip
rm -rf /tmp/java_tools
mkdir /tmp/java_tools
unzip -q -o -d /tmp/java_tools bazel-bin/src/java_tools.zip
touch /tmp/java_tools/WORKSPACE
