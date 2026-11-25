#!/bin/sh
# Sends the application to Apple's notary service, waits for its answer, and staples the ticket to the application, so that macOS does not need
# to ask Apple at its first launch (Compose only notarizes the disk image, and staples the ticket to it).
# Usage: notarize-app.sh <KaTTY.app> <Apple ID> <team ID>, with the app-specific password of the Apple ID in $KATTY_NOTARIZATION_PASSWORD.
set -e
app="$1"
apple_id="$2"
team_id="$3"

fail() {
    echo "notarize-app.sh: $1" >&2
    exit 1
}

[ -d "$app" ] || fail "no application at $app"
[ -n "$apple_id" ] && [ -n "$team_id" ] || fail "the Apple ID and the team ID are needed"
[ -n "$KATTY_NOTARIZATION_PASSWORD" ] || fail "the app-specific password is needed in \$KATTY_NOTARIZATION_PASSWORD"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The notary service takes a zip, a disk image or a package, not a folder. ditto keeps what codesign sealed, which zip does not.
ditto -c -k --keepParent "$app" "$work/app.zip"

xcrun notarytool submit "$work/app.zip" --apple-id "$apple_id" --team-id "$team_id" --password "$KATTY_NOTARIZATION_PASSWORD" \
    --wait --output-format json > "$work/result.json"
status="$(plutil -extract status raw "$work/result.json")"
id="$(plutil -extract id raw "$work/result.json")"
echo "Apple's notary service answered $status for the submission $id"

# notarytool ends well whatever the answer: a refusal is only in the status, and its reasons in the log of the submission.
if [ "$status" != "Accepted" ]; then
    xcrun notarytool log "$id" --apple-id "$apple_id" --team-id "$team_id" --password "$KATTY_NOTARIZATION_PASSWORD" >&2 || true
    fail "the application was not notarized"
fi

xcrun stapler staple "$app"
xcrun stapler validate "$app"
