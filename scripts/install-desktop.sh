#!/usr/bin/env bash
# Build and install the NotiSync desktop commands for the current user.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if [[ -z "${HOME:-}" || "$HOME" != /* || "$HOME" == / ]]; then
    echo "install-desktop: HOME must be an absolute user directory" >&2
    exit 1
fi

resolve_java_home() {
    local java_executable
    if [[ -n "${JAVA_HOME:-}" ]]; then
        if [[ "$JAVA_HOME" != /* ]]; then
            echo "install-desktop: JAVA_HOME must be an absolute path" >&2
            return 1
        fi
        java_executable="$JAVA_HOME/bin/java"
        if [[ ! -x "$java_executable" ]]; then
            echo "install-desktop: JAVA_HOME does not contain executable bin/java: $JAVA_HOME" >&2
            return 1
        fi
    else
        java_executable="$(command -v java 2>/dev/null || true)"
        if [[ -z "$java_executable" || ! -x "$java_executable" ]]; then
            echo "install-desktop: JDK 21 or newer is required; set JAVA_HOME or add java to PATH" >&2
            return 1
        fi
    fi

    local java_settings
    if ! java_settings="$("$java_executable" -XshowSettings:properties -version 2>&1)"; then
        echo "install-desktop: could not inspect Java at $java_executable" >&2
        return 1
    fi

    local reported_java_home java_version java_major resolved_java_home
    reported_java_home="$(printf '%s\n' "$java_settings" | sed -n 's/^[[:space:]]*java\.home[[:space:]]*=[[:space:]]*//p' | head -n 1)"
    java_version="$(printf '%s\n' "$java_settings" | sed -n 's/^[[:space:]]*java\.specification\.version[[:space:]]*=[[:space:]]*//p' | head -n 1)"
    if [[ -z "$reported_java_home" || -z "$java_version" ]]; then
        echo "install-desktop: could not determine the Java home and version for $java_executable" >&2
        return 1
    fi

    if [[ "$java_version" == 1.* ]]; then
        java_major="${java_version#1.}"
        java_major="${java_major%%.*}"
    else
        java_major="${java_version%%.*}"
    fi
    if [[ ! "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 21 )); then
        echo "install-desktop: JDK 21 or newer is required, but Java $java_version was found at $java_executable" >&2
        return 1
    fi

    if [[ "$reported_java_home" != /* ]] ||
        ! resolved_java_home="$(cd -- "$reported_java_home" 2>/dev/null && pwd -P)" ||
        [[ ! -x "$resolved_java_home/bin/java" ]]; then
        echo "install-desktop: Java reported an invalid home directory: $reported_java_home" >&2
        return 1
    fi
    printf '%s\n' "$resolved_java_home"
}

install_dir="${NOTISYNC_INSTALL_DIR:-$HOME/.local/share/notisync}"
bin_dir="${NOTISYNC_BIN_DIR:-$HOME/.local/bin}"
distribution_dir="$project_dir/notisyncd/build/install/notisyncd"
launchers=(notisyncd notisync notisync-gpg nsrun nsscreen)
remembered_java_home="$(resolve_java_home)"

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
if [[ ! -x "$distribution_dir/bin/notisync-screen-helper" ]]; then
    echo "install-desktop: build did not produce bin/notisync-screen-helper" >&2
    exit 1
fi

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
shim_stage_dir="$(mktemp -d "$bin_dir/.notisync-shims.XXXXXX")"
backup_dir=""
daemon_was_running=false

cleanup() {
    if [[ -n "$stage_dir" && -d "$stage_dir" ]]; then
        rm -rf -- "$stage_dir"
    fi
    if [[ -n "$shim_stage_dir" && -d "$shim_stage_dir" ]]; then
        rm -rf -- "$shim_stage_dir"
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

printf -v remembered_java_home_quoted '%q' "$remembered_java_home"
for launcher in "${launchers[@]}"; do
    launcher_target="$install_dir/bin/$launcher"
    printf -v launcher_target_quoted '%q' "$launcher_target"
    {
        printf '%s\n' '#!/usr/bin/env bash'
        printf '%s\n' 'set -euo pipefail'
        printf 'remembered_java_home=%s\n' "$remembered_java_home_quoted"
        printf 'launcher_target=%s\n' "$launcher_target_quoted"
        printf '%s\n' 'if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then'
        printf '%s\n' '    export JAVA_HOME="$remembered_java_home"'
        printf '%s\n' 'fi'
        printf '%s\n' 'exec "$launcher_target" "$@"'
    } > "$shim_stage_dir/$launcher"
    chmod 0755 "$shim_stage_dir/$launcher"
done

if "$distribution_dir/bin/notisyncd" status >/dev/null 2>&1; then
    daemon_was_running=true
    echo "Stopping the running NotiSync daemon..."
    "$distribution_dir/bin/notisyncd" stop
else
    echo "NotiSync daemon is not running."
fi

if [[ -e "$install_dir" || -L "$install_dir" ]]; then
    backup_dir="$(dirname -- "$install_dir")/.notisync-backup.$$"
    mv -- "$install_dir" "$backup_dir"
fi
echo "Installing NotiSync to $install_dir..."
mv -- "$stage_dir" "$install_dir"
stage_dir=""

for launcher in "${launchers[@]}"; do
    mv -f -- "$shim_stage_dir/$launcher" "$bin_dir/$launcher"
done
rmdir -- "$shim_stage_dir"
shim_stage_dir=""

if [[ -n "$backup_dir" ]]; then
    rm -rf -- "$backup_dir"
    backup_dir=""
fi

if [[ "$daemon_was_running" == true ]]; then
    echo "Starting the updated NotiSync daemon..."
    "$install_dir/bin/notisyncd" start
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
