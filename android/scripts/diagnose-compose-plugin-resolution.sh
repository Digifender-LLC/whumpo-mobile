#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."

out=build/diagnostics/compose-plugin-resolution
mkdir -p "$out"
printf 'Gradle wrapper properties SHA-256: ' >"$out/summary.txt"
sha256sum gradle/wrapper/gradle-wrapper.properties >>"$out/summary.txt"
printf '\nJava runtime:\n' >>"$out/summary.txt"
java -version >>"$out/summary.txt" 2>&1

probe="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/compose-plugin-probe.XXXXXX")"
trap 'rm -r -- "$probe"' EXIT
mkdir -p "$probe/project"
cat >"$probe/project/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "compose-plugin-resolution-diagnostic"
EOF
cat >"$probe/project/build.gradle.kts" <<'EOF'
plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
EOF

marker=https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/plugin/compose/org.jetbrains.kotlin.plugin.compose.gradle.plugin/2.4.21/org.jetbrains.kotlin.plugin.compose.gradle.plugin-2.4.21.pom
module=https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/2.4.21/compose-compiler-gradle-plugin-2.4.21.module
for name in marker module; do
  if [[ "$name" == marker ]]; then url=$marker; else url=$module; fi
  set +e
  status="$(curl --silent --show-error --location --max-time 12 --output "$probe/$name.body" --write-out '%{http_code}' "$url" 2>"$probe/$name.curl-error")"
  curl_status=$?
  set -e
  printf '%s HTTP=%s curl_exit=%s URL=%s\n' "$name" "${status:-none}" "$curl_status" "$url" >>"$out/summary.txt"
  if [[ -f "$probe/$name.body" ]]; then
    printf '%s ' "$name body SHA-256/bytes:" >>"$out/summary.txt"
    shasum -a 256 "$probe/$name.body" >>"$out/summary.txt"
  fi
  if [[ -s "$probe/$name.curl-error" ]]; then
    sed -n '1,2p' "$probe/$name.curl-error" >>"$out/summary.txt"
  fi
done

set +e
timeout -k 5s 120s ./gradlew \
  --project-dir "$probe/project" \
  --no-daemon --no-configuration-cache --max-workers=1 --console=plain \
  --stacktrace --info help >"$out/probe.log" 2>&1
gradle_status=$?
set -e
printf 'Gradle plugin-resolution-only help exit=%s\n' "$gradle_status" >>"$out/summary.txt"
printf 'No Android tasks were run; the original build failure remains authoritative.\n' >>"$out/summary.txt"
cat "$out/summary.txt"
exit 0
