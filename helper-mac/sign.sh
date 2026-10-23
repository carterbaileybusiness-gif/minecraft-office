#!/bin/bash
# Signs OfficeHelper.app with one private, persistent, self-signed identity ("Minecraft Office Helper") so macOS
# Screen Recording / Accessibility grants survive rebuilds (an ad-hoc signature changes with every build).
# Port of Home Base's Prototypes/MinecraftBridge/sign-local.py.
#
# The identity lives in ~/Library/Application Support/Minecraft Office/Signing/ (owner-only, never committed).
# No system trust, default keychain, Gatekeeper setting or TCC database is changed: the dedicated keychain is added
# to the user search list only while codesign runs, then the list is restored and the keychain locked.
# Signing only ever happens on Carter's Mac — CI builds with --no-sign.
#
# Usage: sign.sh path/to/OfficeHelper.app
set -euo pipefail
umask 077
app="${1:?usage: sign.sh path/to/OfficeHelper.app}"
[ -d "$app" ] || { echo "sign.sh: no app at $app" >&2; exit 66; }

directory="$HOME/Library/Application Support/Minecraft Office/Signing"
keychain="$directory/office-helper.keychain-db"
password_file="$directory/keychain-password"
certificate="$directory/certificate.pem"
name="Minecraft Office Helper"
mkdir -p "$directory"
chmod 700 "$HOME/Library/Application Support/Minecraft Office" "$directory"

# Runs a command; on failure prints only the tool name and its stderr (arguments may contain the keychain secret).
run() {
  local err
  if ! err=$("$@" 2>&1 >/dev/null); then
    echo "sign.sh: $(basename "$1") failed: $err" >&2
    return 1
  fi
}

search_list() {
  local line
  while IFS= read -r line; do
    line="${line#"${line%%[![:space:]]*}"}"; line="${line#\"}"; line="${line%\"}"
    [ -n "$line" ] && printf '%s\n' "$line"
  done < <(/usr/bin/security list-keychains -d user)
}

previous=()
while IFS= read -r entry; do previous+=("$entry"); done < <(search_list)
restore_search_list() { /usr/bin/security list-keychains -d user -s "${previous[@]}" >/dev/null 2>&1 || true; }

if [ ! -e "$keychain" ]; then
  if [ -e "$password_file" ] || [ -e "$certificate" ]; then
    echo "sign.sh: partial signing setup in $directory; inspect it before replacing the identity. Nothing was changed." >&2
    exit 1
  fi
  password=$(/usr/bin/openssl rand -base64 32 | tr -d '\n/+=')
  printf '%s' "$password" > "$password_file"
  trap restore_search_list EXIT
  run /usr/bin/security create-keychain -p "$password" "$keychain"
  restore_search_list   # create-keychain adds it to the search list; keep the user's list unchanged
  trap - EXIT
  run /usr/bin/security set-keychain-settings -lut 300 "$keychain"
  temporary=$(mktemp -d "$directory/tmp.XXXXXX")
  trap 'rm -rf "$temporary"' EXIT
  cat > "$temporary/certificate.cnf" <<EOF
[req]
distinguished_name=dn
x509_extensions=signing
prompt=no
[dn]
CN=$name
[signing]
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
EOF
  run /usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config "$temporary/certificate.cnf" \
    -keyout "$temporary/private.pem" -out "$certificate"
  run /usr/bin/openssl pkcs12 -export -inkey "$temporary/private.pem" -in "$certificate" \
    -out "$temporary/identity.p12" -passout "file:$password_file"
  run /usr/bin/security import "$temporary/identity.p12" -k "$keychain" -P "$password" -T /usr/bin/codesign
  run /usr/bin/security set-key-partition-list -S apple-tool: -s -k "$password" "$keychain"
  rm -rf "$temporary"
  trap - EXIT
  echo "Created the persistent signing identity \"$name\" in $directory"
else
  if [ ! -e "$password_file" ] || [ ! -e "$certificate" ]; then
    echo "sign.sh: incomplete signing identity in $directory; inspect it before repairing. Nothing was replaced." >&2
    exit 1
  fi
  password=$(cat "$password_file")
fi

finish() { restore_search_list; /usr/bin/security lock-keychain "$keychain" >/dev/null 2>&1 || true; }
trap finish EXIT
run /usr/bin/security unlock-keychain -p "$password" "$keychain"
run /usr/bin/security list-keychains -d user -s "${previous[@]}" "$keychain"
fingerprint=$(/usr/bin/openssl x509 -in "$certificate" -noout -fingerprint -sha1 | sed 's/.*=//; s/://g')
run /usr/bin/codesign --force --sign "$fingerprint" --keychain "$keychain" --timestamp=none "$app"
run /usr/bin/codesign --verify --strict "$app"
echo "Signed with the persistent local identity \"$name\"; no system trust was added."
