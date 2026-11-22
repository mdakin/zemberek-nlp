#!/usr/bin/env bash
set -euo pipefail

cd /home/dndara/work/zemberek-nlp

mvn test-compile -pl experiment -q

CP_FILE="/tmp/zemberek_exp_cp.txt"
if [ ! -f "$CP_FILE" ]; then
  mvn -pl experiment dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -q
fi

CLASSPATH="experiment/target/classes:$(cat "$CP_FILE")"

java -Xmx8G -cp "$CLASSPATH" \
  zemberek.morphology.ambiguity.model.SliceCandidateGenerator \
  "$@"
