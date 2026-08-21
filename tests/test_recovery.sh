#!/usr/bin/env bash
# E2E for the offline password-recovery paths (no Gmail).
# Chạy với một container nvr-wrapper tạm, DB trắng:
#   docker run -d --name nvr-test --network host -e PORT=3999 \
#     -v /tmp/t/data:/app/data -v /tmp/t/config:/app/config nvr-wrapper:latest
#   bash tests/test_recovery.sh
B=http://localhost:3999/api/lock
J='Content-Type: application/json'
CK="$(mktemp)"
rm -f "$CK"

pass=0; fail=0
chk() { # chk <name> <expected-substring> <actual>
  if [[ "$3" == *"$2"* ]]; then echo "  PASS  $1"; pass=$((pass+1));
  else echo "  FAIL  $1"; echo "        expected ~ '$2'"; echo "        got      '$3'"; fail=$((fail+1)); fi
}

echo "── 1. Setup (fresh device) ─────────────────────────────"
R=$(curl -s -c "$CK" -X POST "$B/setup" -H "$J" -d '{"pattern":"1,2,3,6"}')
chk "setup returns success" '"success": true' "$R"
CODE=$(echo "$R" | grep -o '"recoveryCode": "[^"]*"' | cut -d'"' -f4)
echo "        recoveryCode = $CODE"
chk "code format 4x5 groups" "$(echo "$CODE" | grep -qE '^[A-Z0-9]{5}-[A-Z0-9]{5}-[A-Z0-9]{5}-[A-Z0-9]{5}$' && echo ok)" "ok"
chk "no I/L/O/U/0/1 in code" "$(echo "$CODE" | grep -qE '[ILOU01]' && echo bad || echo ok)" "ok"

R=$(curl -s "$B/status")
chk "status hasRecoveryCode" '"hasRecoveryCode": true' "$R"

echo "── 2. Setup guard (takeover attempt) ───────────────────"
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/setup" -H "$J" -d '{"pattern":"9,8,7,4"}')
chk "second setup rejected 409" "409" "$R"

echo "── 3. Pattern verify + escalating per-IP lockout ───────"
R=$(curl -s -X POST "$B/verify" -H "$J" -d '{"pattern":"9,9,9,9"}')
chk "wrong pattern counts down" 'Còn 4 lần thử' "$R"
for i in 2 3 4; do curl -s -o /dev/null -X POST "$B/verify" -H "$J" -d '{"pattern":"9,9,9,9"}'; done
R=$(curl -s -X POST "$B/verify" -H "$J" -d '{"pattern":"9,9,9,9"}')
chk "5th failure locks 60s" 'Khóa 60 giây' "$R"
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/verify" -H "$J" -d '{"pattern":"1,2,3,6"}')
chk "correct pattern blocked while locked" "429" "$R"

echo "── 4. Recovery-code path is NOT blocked by pattern lock ─"
R=$(curl -s -X POST "$B/recovery/code" -H "$J" -d '{"code":"WRONG-WRONG-WRONG-WRONG"}')
chk "wrong code counts down separately" 'Còn 4 lần thử' "$R"

echo "── 5. Recovery with the real code ──────────────────────"
R=$(curl -s -X POST "$B/recovery/code" -H "$J" -d "{\"code\":\"$CODE\"}")
chk "valid code accepted" '"success": true' "$R"
TOK=$(echo "$R" | grep -o '"resetToken": "[^"]*"' | cut -d'"' -f4)
chk "ticket issued" "$([[ -n $TOK ]] && echo ok)" "ok"

R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/reset-complete" -H "$J" -d '{"resetToken":"deadbeef","pattern":"1,4,7,8"}')
chk "forged ticket rejected 403" "403" "$R"

R=$(curl -s -c "$CK" -X POST "$B/reset-complete" -H "$J" -d "{\"resetToken\":\"$TOK\",\"pattern\":\"1,4,7,8\"}")
chk "reset succeeds" '"success": true' "$R"
CODE2=$(echo "$R" | grep -o '"recoveryCode": "[^"]*"' | cut -d'"' -f4)
chk "new code minted" "$([[ -n $CODE2 && $CODE2 != $CODE ]] && echo ok)" "ok"
echo "        new code = $CODE2"

R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/reset-complete" -H "$J" -d "{\"resetToken\":\"$TOK\",\"pattern\":\"1,2,3,6\"}")
chk "ticket is single-use (403)" "403" "$R"

echo "── 6. Old code is dead, new pattern works ──────────────"
R=$(curl -s -X POST "$B/recovery/code" -H "$J" -d "{\"code\":\"$CODE\"}")
chk "old code no longer valid" 'không đúng' "$R"
R=$(curl -s -c "$CK" -X POST "$B/verify" -H "$J" -d '{"pattern":"1,4,7,8"}')
chk "new pattern unlocks" '"success": true' "$R"
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/verify" -H "$J" -d '{"pattern":"1,2,3,6"}')
chk "old pattern rejected" "401" "$R"

echo "── 7. Code normalisation (lowercase / no dashes) ───────"
LOOSE=$(echo "$CODE2" | tr -d '-' | tr 'A-Z' 'a-z')
R=$(curl -s -X POST "$B/recovery/code" -H "$J" -d "{\"code\":\"$LOOSE\"}")
chk "lowercase, dash-less code accepted" '"success": true' "$R"
TOK2=$(echo "$R" | grep -o '"resetToken": "[^"]*"' | cut -d'"' -f4)
curl -s -o /dev/null -X POST "$B/reset-complete" -H "$J" -d "{\"resetToken\":\"$TOK2\",\"pattern\":\"1,4,7,8\"}"

echo "── 8. Auth guard on management endpoints ───────────────"
R=$(curl -s -o /dev/null -w '%{http_code}' "$B/security-status")
chk "security-status needs auth" "401" "$R"
R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/recovery/regenerate" -H "$J" -d '{"pattern":"1,4,7,8"}')
chk "regenerate needs auth" "401" "$R"

curl -s -c "$CK" -o /dev/null -X POST "$B/verify" -H "$J" -d '{"pattern":"1,4,7,8"}'
R=$(curl -s -b "$CK" -o /dev/null -w '%{http_code}' -X POST "$B/recovery/regenerate" -H "$J" -d '{"pattern":"0,0,0,0"}')
chk "regenerate rejects wrong pattern" "401" "$R"
R=$(curl -s -b "$CK" -X POST "$B/recovery/regenerate" -H "$J" -d '{"pattern":"1,4,7,8"}')
chk "regenerate with right pattern" '"recoveryCode"' "$R"
CODE3=$(echo "$R" | grep -o '"recoveryCode": "[^"]*"' | cut -d'"' -f4)

echo "── 9. Security questions ───────────────────────────────"
R=$(curl -s -b "$CK" "$B/questions/catalog")
chk "catalog has 10 questions" "$( [[ $(echo "$R" | grep -o '"id"' | wc -l) -eq 10 ]] && echo ok)" "ok"

R=$(curl -s -b "$CK" -o /dev/null -w '%{http_code}' -X POST "$B/questions/set" -H "$J" \
  -d '{"pattern":"1,4,7,8","items":[{"questionId":"primary_school","answer":"Hà Nội"},{"questionId":"primary_school","answer":"x"},{"questionId":"first_pet","answer":"mun"}]}')
chk "duplicate questions rejected 400" "400" "$R"

R=$(curl -s -b "$CK" -o /dev/null -w '%{http_code}' -X POST "$B/questions/set" -H "$J" \
  -d '{"pattern":"1,4,7,8","items":[{"questionId":"primary_school","answer":"a"},{"questionId":"first_plate","answer":"29A1"},{"questionId":"first_pet","answer":"mun"}]}')
chk "too-short answer rejected 400" "400" "$R"

R=$(curl -s -b "$CK" -X POST "$B/questions/set" -H "$J" \
  -d '{"pattern":"1,4,7,8","items":[{"questionId":"primary_school","answer":"Hà Nội"},{"questionId":"first_plate","answer":"29A1-234"},{"questionId":"first_pet","answer":"Mun"}]}')
chk "questions saved" '"success": true' "$R"

R=$(curl -s "$B/status")
chk "status hasQuestions" '"hasQuestions": true' "$R"

R=$(curl -s "$B/recovery/questions")
chk "public list exposes texts" 'trường tiểu học' "$R"
chk "public list hides answers" "$(echo "$R" | grep -q 'answer' && echo bad || echo ok)" "ok"

echo "── 10. Answering the questions ─────────────────────────"
R=$(curl -s -X POST "$B/recovery/questions" -H "$J" -d '{"answers":["Hà Nội","29A1-234","sai"]}')
chk "one wrong answer fails whole set" 'không chính xác' "$R"
chk "does not reveal which one" "$(echo "$R" | grep -qi 'câu 3\|câu số' && echo bad || echo ok)" "ok"

# Diacritics + case folding: "ha noi" must equal "Hà Nội"
R=$(curl -s -X POST "$B/recovery/questions" -H "$J" -d '{"answers":["  ha   noi ","29a1-234","MUN"]}')
chk "normalised answers accepted" '"success": true' "$R"
TOK3=$(echo "$R" | grep -o '"resetToken": "[^"]*"' | cut -d'"' -f4)
R=$(curl -s -X POST "$B/reset-complete" -H "$J" -d "{\"resetToken\":\"$TOK3\",\"pattern\":\"3,6,9,8\"}")
chk "reset via questions works" '"success": true' "$R"
R=$(curl -s -c "$CK" -X POST "$B/verify" -H "$J" -d '{"pattern":"3,6,9,8"}')
chk "pattern from question-reset works" '"success": true' "$R"

echo "── 11. Regenerated code from step 8 was invalidated ────"
R=$(curl -s -X POST "$B/recovery/code" -H "$J" -d "{\"code\":\"$CODE3\"}")
chk "pre-reset code rejected" 'không đúng' "$R"

echo "── 12. Clear questions ─────────────────────────────────"
R=$(curl -s -b "$CK" -X POST "$B/questions/clear" -H "$J" -d '{"pattern":"3,6,9,8"}')
chk "clear ok" '"success": true' "$R"
R=$(curl -s "$B/status")
chk "status hasQuestions gone" "$(echo "$R" | grep -q '"hasQuestions": true' && echo bad || echo ok)" "ok"

echo "── 13. Gmail endpoints are gone ────────────────────────"
for ep in request-otp reset-request verify-otp; do
  R=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/$ep" -H "$J" -d '{"email":"x@y.z"}')
  chk "/$ep removed (404)" "404" "$R"
done

echo ""
echo "════ $pass passed, $fail failed ════"
[[ $fail -eq 0 ]]
