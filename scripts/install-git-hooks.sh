#!/usr/bin/env bash
# Cài hook pre-commit chặn secret (scripts/git-hooks/pre-commit) vào .git/hooks/.
# .git/hooks/ không được git track nên mỗi máy dev/clone mới phải tự chạy lại file này.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

install -m 755 scripts/git-hooks/pre-commit .git/hooks/pre-commit
echo "OK: đã cài .git/hooks/pre-commit"
