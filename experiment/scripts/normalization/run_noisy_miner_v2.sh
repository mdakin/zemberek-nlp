#!/usr/bin/env bash
set -euo pipefail

# Runs NoisyCorpusMinerV2 against live Zemberek workspace modules (including Emoji support).
# Usage:
#   ./run_noisy_miner_v2.sh --mode sample
#   ./run_noisy_miner_v2.sh --mode full                               # heavy/default -> full_110k/
#   ./run_noisy_miner_v2.sh --mode full --profile medium_and_low      # -> medium_110k/ + low_110k/ (disjoint from full_110k/)
#   ./run_noisy_miner_v2.sh --mode full --profile medium              # -> medium_110k/
#   ./run_noisy_miner_v2.sh --mode full --profile low                 # -> low_110k/

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"

# Ensure core..normalization reactor classes are compiled
if [[ ! -d "${REPO_ROOT}/normalization/target/classes" ]]; then
  mvn test-compile -pl normalization -am -DskipTests -q
fi

CP_CACHE="${REPO_ROOT}/experiment/target/miner_v2_deps.cp"
mkdir -p "${REPO_ROOT}/experiment/target/dataset-classes"

if [[ ! -f "${CP_CACHE}" ]]; then
  mvn -pl apps -am test-compile dependency:build-classpath \
    -Dmdep.excludeGroupIds=zemberek-nlp \
    -Dmdep.outputFile="${CP_CACHE}" -q
fi

REACTOR_CP="${REPO_ROOT}/core/target/classes"
REACTOR_CP+=":${REPO_ROOT}/tokenization/target/classes"
REACTOR_CP+=":${REPO_ROOT}/lm/target/classes"
REACTOR_CP+=":${REPO_ROOT}/lang-id/target/classes"
REACTOR_CP+=":${REPO_ROOT}/morphology/target/classes"
REACTOR_CP+=":${REPO_ROOT}/normalization/target/classes"
FULL_CP="${REACTOR_CP}:$(cat "${CP_CACHE}")"

javac --release 21 -encoding UTF-8 \
  -cp "${FULL_CP}" \
  -d "${REPO_ROOT}/experiment/target/dataset-classes" \
  "${REPO_ROOT}"/experiment/src/main/java/zemberek/normalization/dataset/*.java

exec java -Xmx8g \
  -cp "${REPO_ROOT}/experiment/target/dataset-classes:${FULL_CP}" \
  zemberek.normalization.dataset.NoisyCorpusMinerV2 "$@"
