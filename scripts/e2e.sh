#!/usr/bin/env bash
# End-to-end smoke test against a running server.
#
#   scripts/e2e.sh [port]
#
# Checks the paths a user actually exercises, and prints one line per check.
# Exit status is the number of failures, so it can gate a release.
set -uo pipefail
PORT="${1:-8000}"
BASE="http://localhost:$PORT"
pass=0; fail=0

ok()   { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

check() { # name, expected-status, url, [method], [json-body]
  local name="$1" want="$2" url="$3" method="${4:-GET}" body="${5:-}"
  local got
  if [ -n "$body" ]; then
    got=$(curl -s -o /tmp/e2e-body -w '%{http_code}' -m 20 -X "$method" \
          -H 'content-type: application/json' -d "$body" "$url")
  else
    got=$(curl -s -o /tmp/e2e-body -w '%{http_code}' -m 20 -X "$method" "$url")
  fi
  if [ "$got" = "$want" ]; then ok "$name ($got)"; else
    bad "$name (want $want, got $got): $(head -c 300 /tmp/e2e-body)"; fi
}

jqf() { python3 -c "import json,sys;d=json.load(open('/tmp/e2e-body'));print($1)" 2>/dev/null; }

echo "== core"
check "version"            200 "$BASE/version"
check "health"             200 "$BASE/api/health"
check "ui index"           200 "$BASE/"
check "ui app.js"          200 "$BASE/ui/app.js"

echo "== conversations"
check "list"               200 "$BASE/api/conversations"
check "snapshot"           200 "$BASE/api/conversations/snapshot"
check "search"             200 "$BASE/api/conversations?q=test"
check "unknown id is 404"  404 "$BASE/api/conversation/does-not-exist"

echo "== a real turn"
CODE=$(curl -s -o /tmp/e2e-body -w '%{http_code}' -m 20 -X POST "$BASE/api/conversations/new" \
  -H 'content-type: application/json' \
  -d '{"message":"Use bash to write the word alive into /tmp/e2e-alive.txt, then confirm."}')
if [ "$CODE" = "200" ] || [ "$CODE" = "201" ]; then
  CID=$(jqf "d['conversation']['id']")
  ok "create conversation ($CODE) id=$CID"
  for i in $(seq 1 60); do
    curl -s -m 5 "$BASE/api/conversation/$CID" -o /tmp/e2e-body
    RUN=$(jqf "d.get('running', d['conversation'].get('agent_working'))")
    [ "$RUN" = "False" ] && break; sleep 2
  done
  N=$(jqf "len(d['messages'])")
  [ "${N:-0}" -ge 2 ] && ok "transcript persisted ($N messages)" || bad "transcript ($N messages)"
  if [ -f /tmp/e2e-alive.txt ] && grep -q alive /tmp/e2e-alive.txt; then
    ok "the agent actually ran bash"
  else bad "the agent did not produce /tmp/e2e-alive.txt"; fi
else
  bad "create conversation ($CODE): $(head -c 300 /tmp/e2e-body)"
  CID=""
fi

echo "== workspace"
check "find files"         200 "$BASE/api/find-files?q=README"
check "list directory"     200 "$BASE/api/list-directory?path=."
check "git status"         200 "$BASE/api/git/status"
check "file read"          200 "$BASE/api/file?path=README.md"
check "parent escape"      403 "$BASE/api/file?path=../../etc/passwd"

echo "== models and settings"
check "model providers"    200 "$BASE/api/model-providers"
check "settings"           200 "$BASE/api/settings"

echo "== interface"
# These are the behaviours a reader depends on but no API response reveals.
curl -s -m 10 "$BASE/ui/app.js" -o /tmp/e2e-ui.js
curl -s -m 10 "$BASE/" -o /tmp/e2e-index.html
grep -q "jumpToBottom" /tmp/e2e-ui.js \
  && ok "chat has a jump-to-latest control" || bad "no jump-to-latest control"
grep -q "ResizeObserver" /tmp/e2e-ui.js \
  && ok "transcript tracks its own height" || bad "no height tracking: the bottom will drift"
grep -q 'class="tool-tag"' /tmp/e2e-index.html \
  && ok "tool calls carry a tag" || bad "tool calls are untagged"
grep -q "scrub-bottom" /tmp/e2e-index.html \
  && ok "scrub button is in the markup" || bad "scrub button missing"

if [ -n "${CID:-}" ]; then
  echo "== conversation controls"
  check "archive"          200 "$BASE/api/conversation/$CID/archive" POST '{"archived":true}'
  check "unarchive"        200 "$BASE/api/conversation/$CID/archive" POST '{"archived":false}'
  check "cancel idle"      200 "$BASE/api/conversation/$CID/cancel" POST
  check "usage"            200 "$BASE/api/conversation/$CID/usage"
  check "tags"             200 "$BASE/api/conversation/$CID/tags"
  check "background jobs"  200 "$BASE/api/conversation/$CID/background-jobs"
  check "btw"              200 "$BASE/api/conversation/$CID/btw"
  curl -s -m 10 "$BASE/api/conversation/$CID/usage" -o /tmp/e2e-body
  N=$(jqf "d['llm_calls']")
  if [ "${N:-0}" -ge 1 ]; then ok "usage counted the calls ($N)"; else bad "usage reports $N calls"; fi
fi

echo
echo "$pass passed, $fail failed"
exit "$fail"
