#!/bin/sh
# Sends an application or a disk image to Apple's notary service, waits for its answer, and staples the ticket to it, so that macOS does not need
# to ask Apple at its first launch.
# Usage: notarize.sh <KaTTY.app | KaTTY.dmg>, with the credentials in the environment, never on the command line, which any process can read:
# - an App Store Connect API key (what a CI uses: it belongs to the team, not to a person): $KATTY_NOTARY_KEY (the path of the .p8 file),
#   $KATTY_NOTARY_KEY_ID and $KATTY_NOTARY_ISSUER;
# - else an Apple ID: $KATTY_NOTARY_APPLE_ID, $KATTY_NOTARY_TEAM_ID and $KATTY_NOTARY_PASSWORD (an app-specific password of that Apple ID).
set -e
target="$1"

fail() {
    echo "notarize.sh: $1" >&2
    exit 1
}

[ -e "$target" ] || fail "nothing to notarize at $target"

notary() {
    if [ -n "$KATTY_NOTARY_KEY" ]; then
        xcrun notarytool "$@" --key "$KATTY_NOTARY_KEY" --key-id "$KATTY_NOTARY_KEY_ID" --issuer "$KATTY_NOTARY_ISSUER"
    else
        xcrun notarytool "$@" --apple-id "$KATTY_NOTARY_APPLE_ID" --team-id "$KATTY_NOTARY_TEAM_ID" --password "$KATTY_NOTARY_PASSWORD"
    fi
}

if [ -n "$KATTY_NOTARY_KEY" ]; then
    [ -f "$KATTY_NOTARY_KEY" ] || fail "no API key at $KATTY_NOTARY_KEY"
    [ -n "$KATTY_NOTARY_KEY_ID" ] && [ -n "$KATTY_NOTARY_ISSUER" ] || fail "the API key needs its key ID and its issuer ID"
else
    [ -n "$KATTY_NOTARY_APPLE_ID" ] && [ -n "$KATTY_NOTARY_TEAM_ID" ] && [ -n "$KATTY_NOTARY_PASSWORD" ] \
        || fail "the Apple ID, the team ID and the app-specific password are needed, or an API key"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The notary service takes a zip, a disk image or a package, not a folder. ditto keeps what codesign sealed, which zip does not.
if [ -d "$target" ]; then
    submission="$work/$(basename "$target").zip"
    ditto -c -k --keepParent "$target" "$submission"
else
    submission="$target"
fi

notary submit "$submission" --wait --output-format json > "$work/result.json"
status="$(plutil -extract status raw "$work/result.json")"
id="$(plutil -extract id raw "$work/result.json")"
echo "Apple's notary service answered $status for the submission $id of $(basename "$target")"

# notarytool ends well whatever the answer: a refusal is only in the status, and its reasons in the log of the submission.
if [ "$status" != "Accepted" ]; then
    notary log "$id" >&2 || true
    fail "$target was not notarized"
fi

xcrun stapler staple "$target"
xcrun stapler validate "$target"
