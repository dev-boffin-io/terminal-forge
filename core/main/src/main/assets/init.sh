set -e  # Exit immediately on Failure

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root

if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi


export PS1='\[\033[01;32m\]\u@terminalforge\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
# shellcheck disable=SC2034
export PIP_BREAK_SYSTEM_PACKAGES=1

#fix linker warning
if [[ ! -f /linkerconfig/ld.config.txt ]];then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

if [ "$#" -eq 0 ]; then
    # One-time provisioning so a freshly extracted rootfs has a working shell plus the usual
    # tools. Our Kali rootfs is Debian-based, so this is apt rather than Alpine's apk - upstream
    # ReTerminal runs apk here, which doesn't exist on Debian and would fail on every session.
    # DEBIAN_FRONTEND=noninteractive matters: without it a tzdata-style config prompt would
    # block the login shell forever, since there's nobody at the keyboard to answer it.
    if [ ! -f /etc/terminalforge_provisioned ]; then
        echo "Terminal Forge: first boot setup - apt update, apt upgrade, installing bash curl git"
        if DEBIAN_FRONTEND=noninteractive apt-get update \
            && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y \
            && DEBIAN_FRONTEND=noninteractive apt-get install -y bash curl git; then
            sed -i '/^root:/s|/bin/sh$|/bin/bash|' /etc/passwd
            touch /etc/terminalforge_provisioned
            echo "Terminal Forge: first boot setup finished"
        else
            echo "Terminal Forge: first boot setup failed, will retry next session"
        fi
    fi

    source /etc/profile
    export PS1='\[\033[01;32m\]\u@terminalforge\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
    cd $HOME
    if [ -f /initrc ]; then
        source /initrc
    fi

    # RETERM_LOGIN_SHELL comes from Settings.login_shell via MkSession. Default (0) is whatever
    # the distro uses for root, which on our Debian-based Kali is bash - i.e. unchanged behaviour.
    shell_bin=/bin/bash
    case "${RETERM_LOGIN_SHELL:-0}" in
        1) shell_bin=/bin/bash ;;
        2) shell_bin=/bin/sh ;;
        3) shell_bin=/bin/ash ;;
    esac
    if [ ! -x "$shell_bin" ]; then
        echo "init: $shell_bin is not available in this rootfs, falling back to bash"
        shell_bin=/bin/bash
    fi
    exec "$shell_bin"
else
    exec "$@"
fi