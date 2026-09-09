#!/usr/bin/env bash
set -euo pipefail

reports_dir="build/reports/pluginVerifier"
rm -rf "$reports_dir"

set +e
./gradlew --no-daemon verifyPlugin
gradle_status=$?
set -e

shopt -s globstar nullglob
verdicts=("$reports_dir"/**/verification-verdict.txt)
if [[ ${#verdicts[@]} -eq 0 ]]; then
    printf 'Plugin Verifier produced no verdict.\n' >&2
    exit "${gradle_status:-1}"
fi

for verdict in "${verdicts[@]}"; do
    if ! grep -q '^Compatible\.' "$verdict"; then
        printf 'Plugin Verifier did not report compatibility:\n' >&2
        cat "$verdict" >&2
        exit 1
    fi
    cat "$verdict"
done

internal_reports=("$reports_dir"/**/internal-api-usages.txt)
override_reports=("$reports_dir"/**/override-only-usages.txt)
if [[ ${#internal_reports[@]} -ne 1 || ${#override_reports[@]} -ne 0 ]]; then
    printf 'Plugin Verifier API-risk reports differ from the accepted baseline.\n' >&2
    exit 1
fi

internal_count=$(grep -c '^Internal method ' "${internal_reports[0]}" || true)

if [[ "$internal_count" -ne 1 ]] || \
   ! grep -Fq 'ShowUsagesAction.showUsages' "${internal_reports[0]}"; then
    printf 'Internal API usages differ from the accepted ShowUsagesAction baseline.\n' >&2
    exit 1
fi

if [[ "$gradle_status" -ne 0 ]]; then
    printf 'Plugin Verifier exited non-zero only for the accepted ShowUsagesAction baseline.\n'
fi
