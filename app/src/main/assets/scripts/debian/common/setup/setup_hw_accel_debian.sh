#!/bin/sh
# Compatibility wrapper — Debian component id still points here.
# Implementation lives in setup_hw_accel_guest.sh + altaris_gpu_common.sh
for f in \
    /tmp/setup_hw_accel_guest.sh \
    /usr/local/lib/altarislinux/setup_hw_accel_guest.sh \
    "$(dirname "$0")/setup_hw_accel_guest.sh"
do
    if [ -r "$f" ]; then
        exec /bin/sh "$f" "$@"
    fi
done
echo "Altaris: setup_hw_accel_guest.sh not found — writing virgl"
[ -d /etc/altarislinux ] && [ ! -e /etc/altaris ] && mv /etc/altarislinux /etc/altaris 2>/dev/null || true
mkdir -p /etc/altaris
printf '%s\n' virgl > /etc/altaris/gpu_mode
exit 0
