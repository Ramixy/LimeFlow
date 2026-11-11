#!/usr/bin/env bash
# Собственный «CF-прокси» для TG WS Proxy без Cloudflare.
#
# Cloudflare в схеме tg-ws-proxy — это просто обратный прокси: поддомены
# kwsN.<домен> смотрят на IP дата-центров Telegram, а сами DC отвечают на
# /apiws (WebSocket) и /api (HTTP-транспорт, мультиплексирование медиа).
# Здесь то же самое делает nginx на VPS: TLS снаружи, HTTP к Telegram внутри.
#
# Использование (Debian/Ubuntu, root):
#   bash setup-vps.sh                       # домен <ip>.sslip.io, свой сертификат
#   bash setup-vps.sh example.xyz           # свой домен, свой сертификат
#   bash setup-vps.sh example.xyz me@m.com  # свой домен, Let's Encrypt
# Для своего домена нужны A-записи kws1..kws5, kws203 -> IP этого VPS.
#
# Сертификат движок не проверяет (как и десктопный tg-ws-proxy): трафик
# MTProto зашифрован до самого Telegram, TLS здесь только маскировка.
#
# Последняя строка вывода — TGWS_RESULT ok domain=<домен> или TGWS_RESULT fail …,
# её разбирает установщик в приложении LimeFlow.
set -uo pipefail

RESULT_FILE=/etc/tgws/result
rm -f "$RESULT_FILE"
fail() {
    echo "ОШИБКА: $*" >&2
    mkdir -p /etc/tgws && echo "TGWS_RESULT fail $*" > "$RESULT_FILE"
    echo "TGWS_RESULT fail $*"
    exit 1
}

DOMAIN="${1:-}"
EMAIL="${2:-}"
[[ $EUID -eq 0 ]] || fail "нужны права root"
command -v apt-get >/dev/null || fail "поддерживаются только Debian и Ubuntu"

export DEBIAN_FRONTEND=noninteractive
echo "== Установка пакетов"
apt-get update -q || fail "apt-get update не прошёл"
apt-get install -y -q nginx openssl curl ca-certificates || fail "не удалось установить nginx"

MY_IP="$(curl -4 -fsS --max-time 10 https://api.ipify.org || curl -4 -fsS --max-time 10 https://ifconfig.me || true)"
if [[ -z "$DOMAIN" ]]; then
    [[ -n "$MY_IP" ]] || fail "не удалось узнать внешний IP сервера"
    DOMAIN="${MY_IP//./-}.sslip.io"
    echo "== Домен не указан, используем $DOMAIN"
fi
DOMAIN="${DOMAIN,,}"

declare -A DC_IPS=(
    [1]=149.154.175.50
    [2]=149.154.167.51
    [3]=149.154.175.100
    [4]=149.154.167.91
    [5]=149.154.171.5
    [203]=91.105.192.100
)
DCS=(1 2 3 4 5 203)
NAMES=""
for dc in "${DCS[@]}"; do NAMES="$NAMES kws$dc.$DOMAIN"; done

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 80/tcp >/dev/null
    ufw allow 443/tcp >/dev/null
fi

CERT_DIR=/etc/tgws
CERT="$CERT_DIR/fullchain.pem"
KEY="$CERT_DIR/privkey.pem"
mkdir -p "$CERT_DIR" /var/www/html
CONF=/etc/nginx/sites-available/tgws.conf
rm -f /etc/nginx/sites-enabled/default

issued_le=0
if [[ -n "$EMAIL" ]]; then
    echo "== Let's Encrypt для $DOMAIN"
    apt-get install -y -q certbot >/dev/null || true
    cat > "$CONF" <<EOF
server {
    listen 80;
    server_name$NAMES;
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / { return 404; }
}
EOF
    ln -sf "$CONF" /etc/nginx/sites-enabled/tgws.conf
    nginx -t && systemctl reload nginx
    CERT_ARGS=()
    for dc in "${DCS[@]}"; do CERT_ARGS+=(-d "kws$dc.$DOMAIN"); done
    if certbot certonly --webroot -w /var/www/html --non-interactive --agree-tos \
        -m "$EMAIL" --cert-name tgws --keep-until-expiring "${CERT_ARGS[@]}"; then
        CERT=/etc/letsencrypt/live/tgws/fullchain.pem
        KEY=/etc/letsencrypt/live/tgws/privkey.pem
        issued_le=1
    else
        echo "Let's Encrypt не выдал сертификат, используем свой"
    fi
fi

if [[ $issued_le -eq 0 ]]; then
    echo "== Свой сертификат для *.$DOMAIN"
    if [[ ! -s "$CERT" ]] || ! openssl x509 -in "$CERT" -noout -text | grep -q "\*.$DOMAIN"; then
        openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
            -days 3650 -keyout "$KEY" -out "$CERT" -subj "/CN=$DOMAIN" \
            -addext "subjectAltName=DNS:$DOMAIN,DNS:*.$DOMAIN" >/dev/null 2>&1 \
            || fail "openssl не смог создать сертификат"
        chmod 600 "$KEY"
    fi
fi

echo "== Конфиг nginx"
# Один default_server: принимает любой SNI (LimeFlow шлёт маскировочный, например
# vk.com, потому что провайдер режет sslip.io по SNI) и выбирает DC Telegram по
# заголовку Host, который виден только внутри TLS.
{
cat <<'EOF'
map $http_upgrade $tgws_connection {
    default upgrade;
    ''      close;
}

map $host $tgws_dc {
    ~^kws(1|2|3|4|5|203)\.  $1;
    default                 "";
}

# Режим без TLS: DC приходит в пути /tg/<dc>/apiws, Host — маскировочный.
map $tgws_path_dc $tgws_path_ip {
    1       149.154.175.50;
    2       149.154.167.51;
    3       149.154.175.100;
    4       149.154.167.91;
    5       149.154.171.5;
    203     91.105.192.100;
    default "";
}

map $tgws_dc $tgws_dc_ip {
    1       149.154.175.50;
    2       149.154.167.51;
    3       149.154.175.100;
    4       149.154.167.91;
    5       149.154.171.5;
    203     91.105.192.100;
    default "";
}

EOF
cat <<EOF
server {
    listen 443 ssl http2 default_server;
    server_name _;

    ssl_certificate     $CERT;
    ssl_certificate_key $KEY;
    ssl_protocols TLSv1.2 TLSv1.3;

    access_log off;
    client_max_body_size 8m;

    # /apiws — WebSocket, /api — HTTP-транспорт MTProto (мультиплексирование медиа)
    location ~ ^/(apiws|api)\$ {
        if (\$tgws_dc_ip = "") { return 404; }
        proxy_pass http://\$tgws_dc_ip:80;
        proxy_http_version 1.1;
        proxy_set_header Host kws\$tgws_dc.web.telegram.org;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$tgws_connection;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_connect_timeout 10s;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    location / { return 404; }
}

server {
    listen 80 default_server;
    server_name _;
    access_log off;
    client_max_body_size 8m;
    location /.well-known/acme-challenge/ { root /var/www/html; }

    # Без TLS (LimeFlow «Свой сервер без TLS»): /tg/<dc>/apiws и /tg/<dc>/api
    location ~ ^/tg/(1|2|3|4|5|203)/(apiws|api)\$ {
        set \$tgws_path_dc \$1;
        set \$tgws_path_kind \$2;
        proxy_pass http://\$tgws_path_ip:80/\$tgws_path_kind;
        proxy_http_version 1.1;
        proxy_set_header Host kws\$tgws_path_dc.web.telegram.org;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$tgws_connection;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_connect_timeout 10s;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    location / { return 404; }
}
EOF
} > "$CONF"
ln -sf "$CONF" /etc/nginx/sites-enabled/tgws.conf

nginx -t || fail "nginx отверг конфиг"
systemctl enable nginx >/dev/null 2>&1
systemctl restart nginx || fail "nginx не запустился"

echo "== Проверка: Telegram должен ответить на WebSocket-рукопожатие (101)"
# Так же, как подключается LimeFlow: SNI маскировочный (vk.com), настоящее имя в Host.
ok=0
rm -f "$RESULT_FILE"
for dc in 2 4; do
    code="$(curl -sk -o /dev/null -w '%{http_code}' --http1.1 --max-time 5 \
        --connect-to "vk.com:443:127.0.0.1:443" \
        -H "Host: kws$dc.$DOMAIN" \
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
        -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
        -H 'Sec-WebSocket-Protocol: binary' \
        "https://vk.com/apiws" || true)"
    echo "  kws$dc.$DOMAIN/apiws (SNI vk.com) -> HTTP $code"
    [[ "$code" == "101" ]] && ok=1
done
code="$(curl -s -o /dev/null -w '%{http_code}' --http1.1 --max-time 5 \
    -H 'Host: vk.com' \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    -H 'Sec-WebSocket-Protocol: binary' \
    "http://127.0.0.1/tg/2/apiws" || true)"
echo "  без TLS: /tg/2/apiws (порт 80) -> HTTP $code"
[[ "$code" == "101" ]] && ok=1
[[ $ok -eq 1 ]] || fail "Telegram не ответил через nginx (проверьте, что VPS видит 149.154.0.0/16)"

echo
echo "Готово. Домен для LimeFlow: $DOMAIN"
echo "TGWS_RESULT ok domain=$DOMAIN" > "$RESULT_FILE"
echo "TGWS_RESULT ok domain=$DOMAIN"
