#!/system/bin/sh
# Pox Kernel Manager - zram reconfiguration helper
#
# Linux 4.14 only accepts a block device in /sys/block/zram0/backing_dev and
# refuses disksize/comp_algorithm/backing_dev changes while zram is initialized.
# This script performs the complete, ordered sequence:
#
#   swapoff -> reset -> (loop setup) -> compressor -> limit -> disksize
#   -> mkswap -> swapon
#
# Arguments:
#   $1 backing enabled (0/1)
#   $2 zram size in MB
#   $3 backing file size in MB
#   $4 compressor name (may be empty for the kernel default)
#   $5 writeback limit in MB (0 = no budget)
#   $6 writeback limit enabled (0/1)
#   $7 backing file path
#
# Prints POX_ZRAM_OK on success, POX_ZRAM_ERROR on failure.

SYS=/sys/block/zram0
DEV=/dev/zram0

BACKING="${1:-0}"
SIZE_MB="${2:-0}"
BACKING_MB="${3:-0}"
COMP="${4:-}"
WB_LIMIT_MB="${5:-0}"
WB_ENABLE="${6:-0}"
IMG="${7:-/data/local/tmp/pox-zram-backing.img}"

DONE=0
LOOP=""
OLD_SIZE=$(cat "$SYS/disksize" 2>/dev/null || echo 0)
OLD_COMP=$(sed -n 's/.*\[\([^]]*\)\].*/\1/p' "$SYS/comp_algorithm" 2>/dev/null)
OLD_BACK=$(cat "$SYS/backing_dev" 2>/dev/null || echo none)
[ "$OLD_BACK" = "none" ] && OLD_BACK=""

MKSWAP=$(command -v mkswap 2>/dev/null)
SWAPON=$(command -v swapon 2>/dev/null)
SWAPOFF=$(command -v swapoff 2>/dev/null)
LOSETUP=$(command -v losetup 2>/dev/null)

if [ -z "$MKSWAP" ] || [ -z "$SWAPON" ] || [ -z "$SWAPOFF" ]; then
    if command -v busybox >/dev/null 2>&1; then
        [ -z "$MKSWAP" ] && MKSWAP="busybox mkswap"
        [ -z "$SWAPON" ] && SWAPON="busybox swapon"
        [ -z "$SWAPOFF" ] && SWAPOFF="busybox swapoff"
    fi
fi
if [ -z "$LOSETUP" ] && command -v busybox >/dev/null 2>&1; then
    LOSETUP="busybox losetup"
fi

rollback() {
    echo "POX_ZRAM_ROLLBACK: restoring ${OLD_SIZE}B zram without the new backing store"
    echo 1 > "$SYS/reset" 2>/dev/null
    if [ -n "$LOOP" ] && [ -n "$LOSETUP" ]; then
        $LOSETUP -d "$LOOP" >/dev/null 2>&1 || true
    fi
    if [ -n "$OLD_COMP" ]; then
        echo "$OLD_COMP" > "$SYS/comp_algorithm" 2>/dev/null
    fi
    if [ "${OLD_SIZE:-0}" -gt 0 ] 2>/dev/null; then
        echo "$OLD_SIZE" > "$SYS/disksize" 2>/dev/null
        if [ -n "$MKSWAP" ]; then
            $MKSWAP "$DEV" >/dev/null 2>&1
            $SWAPON "$DEV" >/dev/null 2>&1
        fi
    fi
}

die() {
    echo "POX_ZRAM_ERROR: $*"
    DONE=1
    rollback
    exit 1
}

cleanup_on_exit() {
    RC=$?
    if [ "$DONE" != "1" ] && [ "$RC" != "0" ]; then
        DONE=1
        rollback
    fi
}
trap cleanup_on_exit EXIT

case "$SIZE_MB" in
    ''|*[!0-9]*) die "invalid zram size '$SIZE_MB'" ;;
esac
case "$BACKING_MB" in
    ''|*[!0-9]*) die "invalid backing size '$BACKING_MB'" ;;
esac
[ "$SIZE_MB" -ge 64 ] || die "zram size must be at least 64MB"
[ -n "$MKSWAP" ] || die "mkswap is not available"
[ -n "$SWAPON" ] || die "swapon is not available"
[ -n "$SWAPOFF" ] || die "swapoff is not available"

# swapoff is mandatory: reset refuses an open zram device, and the kernel keeps
# the swap opener until every page has been migrated.
if grep -q "^$DEV " /proc/swaps 2>/dev/null ||
   grep -q "^/dev/block/zram0 " /proc/swaps 2>/dev/null; then
    $SWAPOFF "$DEV" || die "swapoff $DEV failed"
fi

INIT=$(cat "$SYS/initstate" 2>/dev/null || echo 0)
if [ "$INIT" = "1" ] || [ -n "$OLD_BACK" ]; then
    echo 1 > "$SYS/reset" || die "zram reset failed"
fi

# reset() closes the kernel's backing file but does not detach the loop device.
normalize_loop() {
    case "$1" in
        /dev/*) echo "$1" ;;
        '') ;;
        *) echo "/dev/block/$1" ;;
    esac
}
detach_loop() {
    [ -n "$1" ] || return 0
    [ -n "$LOSETUP" ] || return 0
    LOOP_DEV=$(normalize_loop "$1")
    [ -n "$LOOP_DEV" ] || return 0
    $LOSETUP -d "$LOOP_DEV" >/dev/null 2>&1 || true
}
detach_loop "$OLD_BACK"
if [ -n "$LOSETUP" ]; then
    for L in $($LOSETUP -j "$IMG" 2>/dev/null | cut -d: -f1); do
        detach_loop "$L"
    done
fi

if [ "$BACKING" != "1" ]; then
    # No backing requested: release the loop device's file as well.
    rm -f "$IMG" || true
fi
if [ "$BACKING" = "1" ]; then
    [ -n "$LOSETUP" ] || die "losetup is not available"

    mkdir -p "${IMG%/*}" || die "cannot create backing directory"
    BACKING_BYTES=$((BACKING_MB * 1024 * 1024))
    CURRENT_BYTES=0
    [ -f "$IMG" ] && CURRENT_BYTES=$(wc -c < "$IMG" 2>/dev/null | tr -d ' ')
    if [ "${CURRENT_BYTES:-0}" != "$BACKING_BYTES" ]; then
        rm -f "$IMG" || die "cannot remove old backing file"
        if command -v truncate >/dev/null 2>&1; then
            truncate -s "$BACKING_BYTES" "$IMG" || die "cannot create backing file"
        else
            dd if=/dev/zero of="$IMG" bs=1048576 count=0 seek="$BACKING_MB" 2>/dev/null ||
                die "cannot create backing file"
        fi
    fi

    LOOP=$(normalize_loop "$($LOSETUP -f 2>/dev/null)")
    [ -n "$LOOP" ] || die "no free loop device"
    $LOSETUP "$LOOP" "$IMG" || die "cannot attach $IMG to $LOOP"
    echo "$LOOP" > "$SYS/backing_dev" || die "cannot set backing_dev"
fi

if [ -n "$COMP" ]; then
    echo "$COMP" > "$SYS/comp_algorithm" || die "cannot set compressor $COMP"
fi

# writeback_limit is expressed in 4KB units by this kernel. The nodes only
# exist when CONFIG_ZRAM_WRITEBACK is built in.
if [ -e "$SYS/writeback_limit" ]; then
    echo $((WB_LIMIT_MB * 256)) > "$SYS/writeback_limit" ||
        die "cannot set writeback_limit"
    echo "$WB_ENABLE" > "$SYS/writeback_limit_enable" ||
        die "cannot set writeback_limit_enable"
fi

SIZE_BYTES=$((SIZE_MB * 1024 * 1024))
echo "$SIZE_BYTES" > "$SYS/disksize" || die "cannot set disksize"

$MKSWAP "$DEV" >/dev/null 2>&1 || die "mkswap $DEV failed"
$SWAPON -p 100 "$DEV" 2>/dev/null || $SWAPON "$DEV" || die "swapon $DEV failed"

DONE=1
echo "POX_ZRAM_OK size=$SIZE_BYTES backing=$BACKING loop=$LOOP"
exit 0
