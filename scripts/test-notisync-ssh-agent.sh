#!/bin/sh

# End-to-end smoke test for the running NotiSync SSH Agent on POSIX systems.
# Override NOTISYNC_SSH_AGENT_BIN to test a non-installed launcher.

set -u

agent_bin=${NOTISYNC_SSH_AGENT_BIN:-notisync-ssh-agent}
keys_file=
test_key_file=

cleanup() {
    if [ -n "$keys_file" ]; then
        rm -f "$keys_file"
    fi
    if [ -n "$test_key_file" ]; then
        rm -f "$test_key_file"
    fi
}

fail() {
    printf 'FAILED: %s\n' "$*" >&2
    exit 1
}

on_signal() {
    trap - 0
    cleanup
    exit 130
}

trap cleanup 0
trap on_signal 1 2 3 15

if [ "$#" -ne 0 ]; then
    fail "this script does not accept arguments"
fi

command -v "$agent_bin" >/dev/null 2>&1 || fail "cannot find $agent_bin"
command -v ssh-add >/dev/null 2>&1 || fail "cannot find ssh-add"
command -v ssh-keygen >/dev/null 2>&1 || fail "cannot find ssh-keygen"

printf '=== NotiSync SSH Agent sign test ===\n\n'

status_output=$("$agent_bin" status 2>&1)
status_result=$?
printf '%s\n' "$status_output"
if [ "$status_result" -ne 0 ]; then
    fail "NotiSync SSH Agent is not running"
fi

# status reports the bind-time-selected endpoints. Its first endpoint is the
# one exposed by `notisync-ssh-agent env`, including an AUTO fallback selected
# because another agent already owned the preferred socket.
agent_socket=$(printf '%s\n' "$status_output" | sed -n 's/^Endpoint: //p' | sed -n '1p')
if [ -z "$agent_socket" ]; then
    fail "the running agent did not report an endpoint"
fi

SSH_AUTH_SOCK=$agent_socket
export SSH_AUTH_SOCK
printf 'SSH_AUTH_SOCK=%s\n' "$SSH_AUTH_SOCK"

if [ ! -S "$SSH_AUTH_SOCK" ]; then
    fail "the reported endpoint is not a Unix-domain socket: $SSH_AUTH_SOCK"
fi

printf '\nAdvertised keys:\n'
ssh-add -l
list_result=$?
if [ "$list_result" -ne 0 ]; then
    printf '\nSummary:\n'
    printf '  Socket:          %s\n' "$SSH_AUTH_SOCK"
    printf '  Keys discovered: 0\n'
    printf '  Result:          FAILED (could not list identities)\n'
    exit 1
fi

temporary_directory=${TMPDIR:-/tmp}
umask 077
keys_file=$(mktemp "${temporary_directory%/}/notisync-ssh-agent-keys.XXXXXX") || \
    fail "could not create a temporary public-key file"
test_key_file=$(mktemp "${temporary_directory%/}/notisync-ssh-agent-key.XXXXXX") || \
    fail "could not create a temporary per-key file"

if ! ssh-add -L >"$keys_file"; then
    fail "could not retrieve the agent's public keys"
fi

key_count=$(awk 'NF { count++ } END { print count + 0 }' "$keys_file")
if [ "$key_count" -eq 0 ]; then
    fail "the agent returned no public keys"
fi

printf '\nSending one simulated sign-and-verify request per key.\n'
printf 'Approve each request in NotiSync when prompted.\n'

tested=0
passed=0
failed=0

while IFS= read -r public_key || [ -n "$public_key" ]; do
    if [ -z "$public_key" ]; then
        continue
    fi

    tested=$((tested + 1))
    printf '%s\n' "$public_key" >"$test_key_file"
    key_description=$(ssh-keygen -lf "$test_key_file" 2>&1)
    describe_result=$?
    if [ "$describe_result" -ne 0 ]; then
        key_description="unparseable public key: $key_description"
    fi

    printf '\n[%s/%s] %s\n' "$tested" "$key_count" "$key_description"
    sign_output=$(ssh-add -T "$test_key_file" 2>&1)
    sign_result=$?
    if [ "$sign_result" -eq 0 ]; then
        printf '  PASS: agent signature verified\n'
        passed=$((passed + 1))
    else
        printf '  FAIL: sign or verification failed (exit %s)\n' "$sign_result"
        if [ -n "$sign_output" ]; then
            printf '        %s\n' "$sign_output"
        fi
        failed=$((failed + 1))
    fi
done <"$keys_file"

printf '\nSummary:\n'
printf '  Socket:          %s\n' "$SSH_AUTH_SOCK"
printf '  Keys discovered: %s\n' "$key_count"
printf '  Keys tested:     %s\n' "$tested"
printf '  Passed:          %s\n' "$passed"
printf '  Failed:          %s\n' "$failed"

if [ "$failed" -ne 0 ] || [ "$tested" -ne "$key_count" ]; then
    printf '  Result:          FAILED\n'
    exit 1
fi

printf '  Result:          PASSED\n'
