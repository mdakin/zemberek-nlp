#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

CP="$(mvn -pl :zemberek-experiment dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q):$REPO_ROOT/experiment/target/classes:$REPO_ROOT/morphology/target/classes:$REPO_ROOT/core/target/classes:$REPO_ROOT/tokenization/target/classes:$REPO_ROOT/apps/target/classes"

exec java -Xmx10g -cp "$CP" zemberek.morphology.ambiguity.model.AmbiguityProfiler "$@"
