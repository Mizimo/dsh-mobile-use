#!/usr/bin/env bash
# Log in the GitHub CLI with the OAuth device-code flow.
#
# No personal access token is ever typed into the session: `gh` prints a
# one-time code, a human enters it at https://github.com/login/device, and the
# resulting credential is stored in gh's own config — not in this transcript.
set -u

export GIT_SSL_CAINFO="${GIT_SSL_CAINFO:-/usr/local/share/dsha/ca-certificates.crt}"
export SSL_CERT_FILE="${SSL_CERT_FILE:-/usr/local/share/dsha/ca-certificates.crt}"
export GIT_EDITOR=true
export GH_PAGER=cat

echo "=== gh version ==="
gh --version | head -2

echo
echo "=== starting device-code login ==="
echo "opening: https://github.com/login/device"
echo

# Interactive prompts: GitHub.com, HTTPS, authenticate Git with GitHub
# credentials, and log in with a browser (device code).
printf 'GitHub.com\nHTTPS\nY\nLogin with a web browser\n' | gh auth login --hostname github.com

echo
echo "=== status ==="
gh auth status 2>&1
