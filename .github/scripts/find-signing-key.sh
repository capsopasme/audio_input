#!/usr/bin/env bash
#
# Finds the release signing key among the repository secrets, whatever they are called:
# the keystore is the secret whose base64 decodes to a JKS / PKCS#12 file, the passwords are
# the secrets with PASS / PWD in their name that actually open it, the alias comes from a
# secret with ALIAS in its name or from the keystore itself.
# Exports SIGN_KEY_FILE / SIGN_KEY_ALIAS / SIGN_KEY_PWD / SIGN_STORE_PWD / SIGN_STORE_TYPE.
#
# Nothing secret is ever printed: the repository is public, and so are its logs.
#
set -uo pipefail

names=$(jq -r 'keys[] | select(. != "github_token")' <<<"$SECRETS_JSON")
echo "::notice title=Repository secrets visible to this run::$(echo $names)"
value() { jq -r --arg n "$1" '.[$n] // empty' <<<"$SECRETS_JSON"; }

fail_or_skip() {
  if [ -n "${RELEASE_TAG:-}" ] || [[ "${GITHUB_REF:-}" == refs/tags/v* ]]; then
    echo "::error title=Signing::$1; refusing to publish a release signed with a throw-away key"
    exit 1
  fi
  echo "::warning title=Signing::$1; the APK is signed with a debug key"
  exit 0
}

# --- the keystore ------------------------------------------------------------------------------
ks="$RUNNER_TEMP/sign.ks"
ks_name=""
ks_type=""
for n in SIGN_KEY_BASE64 KEYSTORE_BASE64 SIGNING_KEY $names; do
  v=$(value "$n")
  [ ${#v} -gt 100 ] || continue
  printf '%s' "$v" | tr -d ' \r\n\t' | base64 -d > "$ks" 2>/dev/null || continue
  magic=$(head -c4 "$ks" | od -An -tx1 | tr -d ' \n')
  # JKS starts with FEEDFEED, PKCS#12 is a DER sequence (30 82 ..)
  if [ "$magic" = "feedfeed" ]; then
    ks_name=$n; ks_type=JKS; break
  elif [ "${magic:0:4}" = "3082" ]; then
    ks_name=$n; ks_type=PKCS12; break
  fi
done
if [ -z "$ks_name" ]; then
  rm -f "$ks"
  fail_or_skip "No keystore found in the repository secrets"
fi
echo "Keystore: secret $ks_name ($ks_type)"

# --- passwords ---------------------------------------------------------------------------------
candidates=()
for n in SIGN_STORE_PWD SIGN_KEY_PWD KEYSTORE_PASSWORD KEY_STORE_PASSWORD KEY_PASSWORD $names; do
  grep -qxF "$n" <<<"$names" || continue
  case "${n^^}" in
    *PASS*|*PWD*) [[ " ${candidates[*]-} " == *" $n "* ]] || candidates+=("$n") ;;
  esac
done

# A pasted secret can carry stray whitespace or quotes: try the cleaned-up forms as well.
variants() { # variants <value> -> candidate passwords, one per line, raw first
  local v="$1" t
  printf '%s\n' "$v"
  t=$(printf '%s' "$v" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  [ "$t" != "$v" ] && printf '%s\n' "$t"
  case "$t" in \"*\"|\'*\') printf '%s\n' "${t:1:${#t}-2}" ;; esac
  return 0
}

opens_store() { keytool -list -storetype "$ks_type" -keystore "$ks" -storepass "$1" > /dev/null 2>&1; }

store_pwd=""; store_name=""
for n in "${candidates[@]}"; do
  v=$(value "$n")
  [ -n "$v" ] || continue
  while IFS= read -r p; do
    if [ -n "$p" ] && opens_store "$p"; then store_pwd=$p; store_name=$n; break; fi
  done < <(variants "$v")
  [ -n "$store_pwd" ] && break
done

if [ -z "$store_pwd" ]; then
  # Secrets swapped by mistake (e.g. the alias holding the password), or no password at all:
  # every other secret is a cheap guess.
  for n in $names; do
    [ "$n" = "$ks_name" ] && continue
    v=$(value "$n")
    [ ${#v} -le 200 ] || continue
    while IFS= read -r p; do
      if opens_store "$p"; then store_pwd=$p; store_name=$n; break 2; fi
    done < <(variants "$v")
  done
  if [ -z "$store_pwd" ] && opens_store ""; then store_pwd=""; store_name="(empty password)"; fi
  [ -n "$store_name" ] && [[ " ${candidates[*]-} " != *" $store_name "* ]] && candidates+=("$store_name")
fi

if [ -z "$store_pwd" ] && [ -z "$store_name" ] && [ "$ks_type" = PKCS12 ]; then
  # A PKCS#12 file keytool can't read (e.g. legacy RC2 encryption from an old OpenSSL): if
  # OpenSSL opens it, re-pack it with algorithms Java reads, keeping the same password.
  for n in "${candidates[@]}"; do
    v=$(value "$n")
    [ -n "$v" ] || continue
    while IFS= read -r p; do
      [ -n "$p" ] || continue
      for legacy in "" "-legacy"; do
        if PW="$p" openssl pkcs12 $legacy -in "$ks" -passin env:PW -nodes -out "$RUNNER_TEMP/k.pem" > /dev/null 2>&1; then
          if PW="$p" openssl pkcs12 -export -in "$RUNNER_TEMP/k.pem" -out "$ks.new" -passout env:PW \
              -keypbe AES-256-CBC -certpbe AES-256-CBC -macalg sha256 > /dev/null 2>&1; then
            mv "$ks.new" "$ks"
            echo "Re-packed the PKCS#12 keystore for Java (OpenSSL${legacy:+ legacy})"
          fi
          rm -f "$RUNNER_TEMP/k.pem"
          if opens_store "$p"; then store_pwd=$p; store_name=$n; break 3; fi
        fi
      done
    done < <(variants "$v")
  done
fi

if [ -z "$store_pwd" ] && [ -z "$store_name" ]; then
  # explain, without revealing anything secret
  size=$(stat -c %s "$ks")
  why=""
  for n in "${candidates[@]}"; do
    v=$(value "$n")
    flags=""
    [ -z "$v" ] && flags+=" empty"
    [[ "$v" =~ ^[[:space:]] || "$v" =~ [[:space:]]$ ]] && flags+=" leading-or-trailing-whitespace"
    [[ "$v" == \"*\" || "$v" == \'*\' ]] && flags+=" quoted"
    err=$(keytool -list -storetype "$ks_type" -keystore "$ks" -storepass "$v" 2>&1 \
      | grep -v JAVA_TOOL | grep -i -m1 'error\|exception' | tr -cd '[:print:]' | cut -c1-160)
    why+="$n:${flags:- looks normal} -> keytool: ${err:-?}%0A"
  done
  fail_or_skip "Found the keystore ($ks_name, $ks_type, $size bytes) but none of the password secrets opens it.%0A$why"
fi
echo "Keystore password: secret $store_name"

# --- alias -------------------------------------------------------------------------------------
aliases=$(keytool -list -storetype "$ks_type" -keystore "$ks" -storepass "$store_pwd" 2>/dev/null \
  | awk -F, '/PrivateKeyEntry/ {print $1}')
alias=""
for n in SIGN_KEY_ALIAS KEY_ALIAS ALIAS $names; do
  case "${n^^}" in
    *ALIAS*)
      v=$(value "$n" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
      if [ -n "$v" ] && grep -qixF "$v" <<<"$aliases"; then
        alias=$(grep -ixF -m1 "$v" <<<"$aliases")
        break
      fi
      ;;
  esac
done
[ -n "$alias" ] || alias=$(head -n1 <<<"$aliases")
[ -n "$alias" ] || fail_or_skip "The keystore in $ks_name has no private key"
echo "Key alias: $( [ "$(wc -l <<<"$aliases")" -gt 1 ] && echo "one of several" || echo "the only one" )"

# --- the key password: whichever candidate unlocks the key (often the store's) ------------------
key_pwd=""
p12="$RUNNER_TEMP/probe.p12"
for n in "$store_name" "${candidates[@]}"; do
  v=$(value "$n")
  [ -n "$v" ] || continue
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    rm -f "$p12"
    if keytool -importkeystore -noprompt -srcstoretype "$ks_type" -srckeystore "$ks" \
        -srcstorepass "$store_pwd" -srcalias "$alias" -srckeypass "$p" \
        -destkeystore "$p12" -deststoretype PKCS12 -deststorepass probe123 -destkeypass probe123 \
        > /dev/null 2>&1; then
      key_pwd=$p
      echo "Key password: secret $n"
      break 2
    fi
  done < <(variants "$v")
done
rm -f "$p12"
[ -n "$key_pwd" ] || fail_or_skip "None of the password secrets unlocks the key in $ks_name"

{
  echo "SIGN_KEY_FILE=$ks"
  echo "SIGN_KEY_ALIAS=$alias"
  echo "SIGN_KEY_PWD=$key_pwd"
  echo "SIGN_STORE_PWD=$store_pwd"
  echo "SIGN_STORE_TYPE=$ks_type"
} >> "$GITHUB_ENV"
echo "::notice title=Signing::Using the key from secret $ks_name"
