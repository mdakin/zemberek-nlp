#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"

CP_FILE="/tmp/zemberek_experiment_cp.txt"
if [[ ! -f "${CP_FILE}" ]]; then
  mvn -q dependency:build-classpath -pl :zemberek-experiment -Dmdep.outputFile="${CP_FILE}"
fi

CLASSPATH="experiment/target/classes:$(cat "${CP_FILE}")"

java -Xmx14G -XX:+UseG1GC -cp "${CLASSPATH}" \
  zemberek.morphology.ambiguity.model.SentenceSelector "$@"
