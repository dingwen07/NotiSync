#!/usr/bin/env bash
# op-ssh-add — add a 1Password SSH key to the SSH agent (ssh-add).
#
# The private key is streamed straight from 1Password into ssh-add on stdin and is
# never written to disk. Accepts any of:
#
#   op-ssh-add 'Test SSH Key'                                           item name
#   op-ssh-add pyxk4slwrfhf4ef2cfgco2dq4m                               item UUID
#   op-ssh-add 'Personal/Test SSH Key'                                  vault/item
#   op-ssh-add 'op://Personal/Test SSH Key/private key'                 human secret reference
#   op-ssh-add 'op://Personal/pyxk4slwrfhf4ef2cfgco2dq4m/private_key'   copy-secret-reference form
#
# The first three forms resolve the item's private key field automatically
# (PRIVATE_KEY purpose, falling back to a "private key" label/id match).
# Requires: 1Password CLI (op), OpenSSH ssh-add; item lookups also need jq.
set -euo pipefail

die() {
    printf 'op-ssh-add: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage: op-ssh-add ITEM_OR_REFERENCE

Adds the private key of a 1Password SSH Key item to the SSH agent (ssh-add),
streaming the key directly from 1Password without writing it to disk.

ITEM_OR_REFERENCE may be:
  Test SSH Key                                    item name
  pyxk4slwrfhf4ef2cfgco2dq4m                      item UUID
  Personal/Test SSH Key                           vault/item
  op://Personal/Test SSH Key/private key          human secret reference
  op://Personal/pyxk4slwrfhf4ef2cfgco2dq4m/private_key   copy-secret-reference form

Requires the 1Password CLI (op) and ssh-add; item-name lookups also need jq.
EOF
}

arg="${1:-}"
case "$arg" in
    -h | --help) usage; exit 0 ;;
    '') usage >&2; exit 1 ;;
esac

command -v op >/dev/null 2>&1 || die "the 1Password CLI (op) was not found"
command -v ssh-add >/dev/null 2>&1 || die "ssh-add was not found"

# A full secret reference (vault/item/field) is passed through unchanged: op accepts
# both the human form ("op://Vault/Item/private key") and the stable reference form
# ("op://Vault/<item-id>/private_key", as produced by "copy secret reference").
if [[ "$arg" == op://*/*/* ]]; then
    op read "$arg" | ssh-add - || die "failed to add the SSH key from '$arg'"
    printf 'op-ssh-add: added SSH key from %s\n' "$arg" >&2
    exit 0
fi

# Anything else names an item: resolve its private key field, then stream it.
command -v jq >/dev/null 2>&1 ||
    die "jq is required for item lookups (brew install jq); pass a full op:// reference instead"

item_ref="$arg"
[[ "$item_ref" != op://* && "$item_ref" == */* ]] && item_ref="op://$item_ref" # "Vault/Item" shorthand

item_meta="$(
    op item get "$item_ref" --format json 2>/dev/null | jq -r '
        [
            .id,
            .vault.id,
            (
                [.fields[] | select(.purpose == "PRIVATE_KEY")][0].id
                // ([.fields[] | select(((.label // "") | ascii_downcase | gsub("[ _-]"; "")) == "privatekey")][0].id)
                // ([.fields[] | select(((.id // "") | ascii_downcase | gsub("[ _-]"; "")) == "privatekey")][0].id)
            ) // "",
            (
                [.fields[] | select(.purpose == "PRIVATE_KEY")][0].label
                // ([.fields[] | select(((.label // "") | ascii_downcase | gsub("[ _-]"; "")) == "privatekey")][0].label)
            ) // ""
        ] | @tsv
    '
)" || die "could not read item '$item_ref' (is op signed in?)"

IFS=$'\t' read -r item_id vault_id field_ref field_label <<<"$item_meta"
if [[ -z "$item_id" || -z "$vault_id" || -z "$field_ref" ]]; then
    die "no private key field found on item '$item_ref'"
fi

secret_ref="op://$vault_id/$item_id/$field_ref"
if ! op read "$secret_ref" | ssh-add -; then
    if [[ -n "$field_label" && "$field_label" != "$field_ref" ]]; then
        # Fallback: address the field by its label instead of its stable reference.
        op read "op://$vault_id/$item_id/$field_label" | ssh-add - ||
            die "failed to add the SSH key from '$item_ref'"
    else
        die "failed to add the SSH key from '$item_ref'"
    fi
fi
printf 'op-ssh-add: added SSH key from item %s\n' "$item_ref" >&2
