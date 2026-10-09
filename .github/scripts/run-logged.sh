#!/usr/bin/env bash
#
# run-logged.sh <command...>
#
# Runs a command, keeping its output in the log as usual. If it fails, the interesting part of
# the output (compiler errors, Gradle's "What went wrong", the last lines) is also reported as
# error annotations, which show up on the run's summary page and in the checks API without
# having to download the full log.
#
set -uo pipefail

log=$(mktemp)
"$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
[ "$status" -eq 0 ] && exit 0

# workflow commands need %, CR and LF escaped
esc() {
  local s="$1"
  s="${s//'%'/'%25'}"
  s="${s//$'\r'/'%0D'}"
  s="${s//$'\n'/'%0A'}"
  printf '%s' "$s"
}

annotate() { # annotate <title> <text>
  [ -n "$2" ] || return 0
  echo "::error title=$1::$(esc "$(printf '%s' "$2" | head -c 6000)")"
}

# Kotlin / Java / C++ compiler errors, AAPT and R8 errors
annotate "compiler errors" "$(grep -E '^e: |error:|ERROR:|^FAILED: |ld\.lld: error' "$log" | grep -v '^warning' | head -40)"
# Gradle's own explanation
annotate "gradle" "$(awk '/What went wrong:/{f=1} f{print} /^\* Try:/{exit}' "$log" | head -40)"
# CMake
annotate "cmake" "$(grep -E -A5 '^CMake Error' "$log" | head -40)"
annotate "last lines" "$(tail -n 40 "$log")"
exit "$status"
