#!/system/bin/sh
# Pox Kernel Manager - AnyKernel3 runner
#
# $1 = workspace containing busybox + update-binary
# $2 = AnyKernel3 ZIP
#
# AnyKernel3's update-binary is written for recovery, but its boot-mode path is
# designed to work from a rooted Android system as well. It unpacks the live
# boot image with magiskboot, injects the package contents, repacks, and writes
# the result to the boot partition.

F="$1"
Z="$2"
BB="$F/busybox"
TMP="$F/tmp"

[ -n "$F" ] && [ -n "$Z" ] || exit 2
[ -x "$BB" ] || chmod 755 "$BB" || exit 2
mkdir -p "$F/bin" || exit 2

# Put busybox applets (including unzip and ash) ahead of toybox. update-binary
# calls `unzip -o`; using the package's own busybox removes any dependency on
# the ROM's toybox build.
"$BB" --install -s "$F/bin" >/dev/null 2>&1
export PATH="$F/bin:$PATH"

"$BB" umount "$TMP" 2>/dev/null
"$BB" rm -rf "$TMP" 2>/dev/null
"$BB" mkdir -p "$TMP" || exit 2

# A tmpfs keeps the unpacked installer out of /data and is what KernelFlasher
# uses. Fall back to the workspace if a ROM refuses the mount.
if "$BB" mount -t tmpfs -o noatime,exec tmpfs "$TMP" 2>/dev/null; then
    MOUNTED=1
else
    MOUNTED=0
fi

AKHOME="$TMP/anykernel"
"$BB" ash "$F/update-binary" 3 1 "$Z"
RC=$?

if [ "$MOUNTED" = "1" ]; then
    "$BB" umount "$TMP" 2>/dev/null
fi
"$BB" rm -rf "$TMP" 2>/dev/null
"$BB" rm -f "$F/update-binary" "$F/busybox" 2>/dev/null

exit $RC
