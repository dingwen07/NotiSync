#!/usr/bin/env bash
# Build and install the NotiSync desktop commands for the current user.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if [[ -z "${HOME:-}" || "$HOME" != /* || "$HOME" == / ]]; then
    echo "install-desktop: HOME must be an absolute user directory" >&2
    exit 1
fi

install_dir="${NOTISYNC_INSTALL_DIR:-$HOME/.local/share/notisync}"
bin_dir="${NOTISYNC_BIN_DIR:-$HOME/.local/bin}"
distribution_dir="$project_dir/notisyncd/build/install/notisyncd"
launchers=(notisyncd notisync nsrun)

if [[ "$install_dir" != /* || "$bin_dir" != /* ]]; then
    echo "install-desktop: install directories must be absolute paths" >&2
    exit 1
fi

echo "Building the NotiSync desktop distribution..."
"$project_dir/gradlew" -p "$project_dir" :notisyncd:installDist --console=plain "$@"

for launcher in "${launchers[@]}"; do
    if [[ ! -x "$distribution_dir/bin/$launcher" ]]; then
        echo "install-desktop: build did not produce bin/$launcher" >&2
        exit 1
    fi
done

mkdir -p -- "$(dirname -- "$install_dir")" "$bin_dir"

for launcher in "${launchers[@]}"; do
    link="$bin_dir/$launcher"
    if [[ -e "$link" && ! -f "$link" && ! -L "$link" ]]; then
        echo "install-desktop: refusing to replace non-file path $link" >&2
        exit 1
    fi
done

# Stage next to the destination so the final rename stays on one filesystem.
stage_dir="$(mktemp -d "$(dirname -- "$install_dir")/.notisync-install.XXXXXX")"
backup_dir=""

cleanup() {
    if [[ -n "$stage_dir" && -d "$stage_dir" ]]; then
        rm -rf -- "$stage_dir"
    fi
    if [[ -n "$backup_dir" && ( -e "$backup_dir" || -L "$backup_dir" ) ]]; then
        if [[ ! -e "$install_dir" && ! -L "$install_dir" ]]; then
            mv -- "$backup_dir" "$install_dir"
        else
            rm -rf -- "$backup_dir"
        fi
    fi
}
trap cleanup EXIT HUP INT TERM

cp -R "$distribution_dir/." "$stage_dir/"

if "$distribution_dir/bin/notisyncd" status >/dev/null 2>&1; then
    echo "Stopping the running NotiSync daemon..."
    "$distribution_dir/bin/notisyncd" stop
fi

if [[ -e "$install_dir" || -L "$install_dir" ]]; then
    backup_dir="$(dirname -- "$install_dir")/.notisync-backup.$$"
    mv -- "$install_dir" "$backup_dir"
fi
mv -- "$stage_dir" "$install_dir"
stage_dir=""

for launcher in "${launchers[@]}"; do
    ln -sfn -- "$install_dir/bin/$launcher" "$bin_dir/$launcher"
done

if [[ -n "$backup_dir" ]]; then
    rm -rf -- "$backup_dir"
    backup_dir=""
fi

trap - EXIT HUP INT TERM

echo "Installed NotiSync in $install_dir"
printf 'Installed commands:'
printf ' %s' "${launchers[@]}"
printf '\n'

case ":${PATH:-}:" in
    *":$bin_dir:"*) ;;
    *) echo "Add $bin_dir to PATH to run the commands." ;;
esac
