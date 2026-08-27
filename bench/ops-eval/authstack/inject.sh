#!/usr/bin/env bash
# Inject / heal the authstack command-line auth fault, as a compose OVERRIDE file.
#
# Not an env var and not a conditional command, both of which were tried and both of which broke the
# fixture in different ways:
#   * a shell-exported var is dropped by R3's own `compose up --force-recreate`, which then HEALS the
#     fault it was meant to restore;
#   * a conditional command leaves `--requirepass` in .Config.Cmd even when unset, so anything reading
#     the launch config sees a password that is not in effect — it mis-fired a card.
# An override file keeps .Config.Cmd honest in both states, which is what a real stack looks like.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
case "${1:-}" in
  inject) docker compose -p authstack -f docker-compose.yml -f docker-compose.fault.yml \
            up -d --force-recreate cache >/dev/null 2>&1 ;;
  heal)   docker compose -p authstack -f docker-compose.yml \
            up -d --force-recreate cache >/dev/null 2>&1 ;;
  *) echo "usage: inject.sh inject|heal" >&2; exit 2 ;;
esac
sleep 12
curl -s -m10 localhost:28090/ | head -c 160; echo
