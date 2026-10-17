#!/usr/bin/env bash
# Runs on the VPS as root. Invoked by setup.ps1 with the uploaded files in $1.
set -euo pipefail

STAGE=${1:?staging directory}
APP_DIR=/opt/mcchat
ETC_DIR=/etc/mcchat
DOMAIN=mcchat.daruks.com

if ! id mcchat >/dev/null 2>&1; then
    useradd --system --home-dir /var/lib/mcchat --shell /usr/sbin/nologin mcchat
fi

install -d -o root -g root -m 0755 "$APP_DIR"
install -o root -g root -m 0755 "$STAGE/mcchat-server" "$APP_DIR/mcchat-server"

install -d -o root -g mcchat -m 0750 "$ETC_DIR"
if [ ! -f "$ETC_DIR/config.json" ]; then
    install -o root -g mcchat -m 0640 "$STAGE/config.example.json" "$ETC_DIR/config.json"
    echo "created $ETC_DIR/config.json"
fi
if [ ! -f "$ETC_DIR/mcchat.env" ]; then
    umask 077
    printf 'MCCHAT_KEY=%s\nMCCHAT_KEY_ID=1\n' "$("$APP_DIR/mcchat-server" keygen)" > "$ETC_DIR/mcchat.env"
    chown root:root "$ETC_DIR/mcchat.env"
    chmod 0600 "$ETC_DIR/mcchat.env"
    echo "generated $ETC_DIR/mcchat.env -- BACK IT UP (messages are unreadable without it)"
fi

install -o root -g root -m 0644 "$STAGE/mcchat.service" /etc/systemd/system/mcchat.service
systemctl daemon-reload
systemctl enable mcchat >/dev/null
systemctl restart mcchat

install -o root -g root -m 0644 "$STAGE/$DOMAIN.conf" "/etc/nginx/sites-available/$DOMAIN"
ln -sf "/etc/nginx/sites-available/$DOMAIN" "/etc/nginx/sites-enabled/$DOMAIN"
nginx -t
systemctl reload nginx

rm -rf "$STAGE"
sleep 1
systemctl --no-pager --lines=5 status mcchat || true
curl -fsS http://127.0.0.1:25566/healthz
