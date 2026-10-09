#!/usr/bin/env bash
#
# Finds the release signing key among the repository secrets, whatever they are called:
# the keystore is the secret whose base64 decodes to a JKS / PKCS#12 file, the passwords are
# the secrets with PASS / PWD in their name that actually open it, the alias comes from a
# secret with ALIAS in its name or from the keystore itself.
# Exports SIGN_KEY_FILE / SIGN_KEY_ALIAS / SIGN_KEY_PWD / SIGN_STORE_PWD for Gradle.
# Secret values are never printed (GitHub masks them in the log anyway).
#
set -uo pipefail

names=$(jq -r 'keys[] | select(. != "github_token")' <<<"$SECRETS_JSON")
echo "::notice title=Repository secrets visible to this run::$(echo $names)"
value() { jq -r --arg n "$1" '.[$n] // empty' <<<"$SECRETS_JSON"; }

ks="$RUNNER_TEMP/sign.ks"
ks_name=""
ks_type=""
# preferred names first, then everything else
for n in SIGN_KEY_BASE64 KEYSTORE_BASE64 SIGNING_KEY $names; do
  v=$(value "$n")
  [ ${#v} -gt 100 ] || continue
  printf '%s' "$v" | tr -d ' \r\n' | base64 -d > "$ks" 2>/dev/null || continue
  magic=$(head -c4 "$ks" | od -An -tx1 | tr -d ' \n')
  # JKS starts with FEEDFEED, PKCS#12 is a DER sequence (30 82 ..)
  if [ "$magic" = "feedfeed" ]; then
    ks_name=$n; ks_type=JKS
    break
  elif [ "${magic:0:4}" = "3082" ]; then
    ks_name=$n; ks_type=PKCS12
    break
  fi
done

if [ -z "$ks_name" ]; then
  rm -f "$ks"
  if [[ "${GITHUB_REF:-}" == refs/tags/v* ]]; then
    echo "::error title=Signing::No keystore found in the repository secrets; refusing to publish a release signed with a throw-away key"
    exit 1
  fi
  echo "::warning title=Signing::No keystore found in the repository secrets, the APK is signed with a debug key"
  exit 0
fi
echo "Keystore: secret $ks_name"

candidates=()
for n in SIGN_STORE_PWD SIGN_KEY_PWD KEYSTORE_PASSWORD KEY_STORE_PASSWORD KEY_PASSWORD $names; do
  case "${n^^}" in *PASS*|*PWD*) candidates+=("$n") ;; esac
done

store_pwd=""; store_name=""
for n in "${candidates[@]}"; do
  v=$(value "$n")
  [ -n "$v" ] || continue
  if keytool -list -keystore "$ks" -storepass "$v" > /dev/null 2>&1; then
    store_pwd=$v; store_name=$n
    break
  fi
done
if [ -z "$store_pwd" ]; then
  echo "::error title=Signing::Found the keystore ($ks_name) but none of the password secrets opens it"
  exit 1
fi
echo "Keystore password: secret $store_name"

aliases=$(keytool -list -keystore "$ks" -storepass "$store_pwd" 2>/dev/null | awk -F, '/PrivateKeyEntry/ {print $1}')
alias=""
for n in SIGN_KEY_ALIAS KEY_ALIAS ALIAS $names; do
  case "${n^^}" in
    *ALIAS*)
      v=$(value "$n")
      if [ -n "$v" ] && grep -qxF "$v" <<<"$aliases"; then alias=$v; break; fi
      ;;
  esac
done
[ -n "$alias" ] || alias=$(head -n1 <<<"$aliases")
if [ -z "$alias" ]; then
  echo "::error title=Signing::The keystore has no private key"
  exit 1
fi

# the key password: whichever candidate unlocks the key (often the same as the store's)
key_pwd=""
p12="$RUNNER_TEMP/probe.p12"
for n in "$store_name" "${candidates[@]}"; do
  v=$(value "$n")
  [ -n "$v" ] || continue
  rm -f "$p12"
  if keytool -importkeystore -noprompt -srckeystore "$ks" -srcstorepass "$store_pwd" -srcalias "$alias" \
      -srckeypass "$v" -destkeystore "$p12" -deststoretype PKCS12 -deststorepass probe123 \
      -destkeypass probe123 > /dev/null 2>&1; then
    key_pwd=$v
    echo "Key password: secret $n"
    break
  fi
done
rm -f "$p12"
if [ -z "$key_pwd" ]; then
  echo "::error title=Signing::None of the password secrets unlocks the key in $ks_name"
  exit 1
fi

{
  echo "SIGN_KEY_FILE=$ks"
  echo "SIGN_KEY_ALIAS=$alias"
  echo "SIGN_KEY_PWD=$key_pwd"
  echo "SIGN_STORE_PWD=$store_pwd"
  echo "SIGN_STORE_TYPE=$ks_type"
} >> "$GITHUB_ENV"
echo "::notice title=Signing::Using the key from secret $ks_name"
