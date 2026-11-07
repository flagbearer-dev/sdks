#!/usr/bin/env bash
# Packs the SDKs into $1 and tests them in a clean project outside the
# workspace.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$1"
consumer="$(mktemp -d)"
trap 'rm -rf "$consumer"' EXIT

mkdir -p "$out"
# sdk-core first: the evaluator depends on it.
for package in core evaluator web node; do
  (cd "$root/js/$package" && bun pm pack --destination "$out")
done

cd "$consumer"
echo '{ "name": "consumer", "private": true, "type": "module" }' > package.json
npm install --no-audit --no-fund "$out"/*.tgz typescript @types/node

cat > tsconfig.json <<'JSON'
{
  "compilerOptions": {
    "strict": true,
    "noEmit": true,
    "module": "nodenext",
    "moduleResolution": "nodenext",
    "target": "es2022",
    "lib": ["es2022", "dom"],
    "skipLibCheck": false,
    "types": ["node"]
  },
  "files": ["consumer.ts"]
}
JSON

cat > consumer.ts <<'TS'
import { init as initNode } from "@flagbearer/node";
import { init as initWeb } from "@flagbearer/web";

export const browser: boolean = initWeb({
  clientKey: "fbc_test",
  user: { key: "1" },
}).isEnabled("flag", false);
export const server: boolean = initNode({ key: "fbs_test" }).isEnabled(
  "flag",
  { key: "1" },
  false,
);
TS
npx tsc -p .

cat > run.mjs <<'JS'
import { init } from "@flagbearer/node";

const flags = init({ key: "fbs_test", url: "http://127.0.0.1:9" });
const enabled = flags.isEnabled("flag", { key: "1" }, true);
flags.close();

if (!enabled) {
  throw new Error("Expected the application default");
}
JS
node run.mjs

echo "SDK packages verified in $out"
