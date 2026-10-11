#!/bin/sh
# Сертификаты для взаимного TLS (mTLS) между сервисами.
#
# Создаёт свой удостоверяющий центр (CA) и по сертификату на сервис, подписанному им.
# Имя сервиса — в CN: по нему gRPC-сервер узнаёт, кто к нему пришёл, и решает, пускать ли.
# В SAN — DNS-имена, по которым сервис доступен (имя в сети compose и localhost): клиент
# сверяет их с адресом, к которому подключается, и не поверит сертификату с чужим именем.
#
# Идемпотентно: если CA уже есть, ничего не делает. Пересоздать — удалить каталог (или том)
# с сертификатами и запустить снова: новые сертификаты подпишет новый CA.
#
# Это локальный CA для разработки. В бою сертификаты выдаёт настоящий центр — cert-manager
# в Kubernetes, Vault PKI, сервисная сеть вроде Istio, — и живут они часы, а не годы.
#
# Использование: generate-certs.sh <каталог> <сервис> [<сервис>...]
set -eu

OUT="$1"
shift

mkdir -p "$OUT"

if [ -f "$OUT/ca.crt" ]; then
    echo "Сертификаты уже есть в $OUT — ничего не делаю"
    exit 0
fi

DAYS="${CERT_DAYS:-825}"

# Удостоверяющий центр. Его закрытый ключ нужен только здесь, сервисам он не раздаётся.
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days "$DAYS" \
    -subj "/CN=food-delivery-grpc-ca" \
    -keyout "$OUT/ca.key" -out "$OUT/ca.crt" 2>/dev/null

for service in "$@"; do
    cat > "$OUT/$service.ext" <<EOF
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth, clientAuth
subjectAltName = DNS:$service, DNS:localhost
EOF

    # Ключ сразу в PKCS#8 (BEGIN PRIVATE KEY): этот формат понимают и Spring, и Netty.
    openssl req -newkey rsa:2048 -nodes -sha256 \
        -subj "/CN=$service" \
        -keyout "$OUT/$service.key" -out "$OUT/$service.csr" 2>/dev/null

    openssl x509 -req -sha256 -days "$DAYS" \
        -in "$OUT/$service.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
        -extfile "$OUT/$service.ext" -out "$OUT/$service.crt" 2>/dev/null

    rm -f "$OUT/$service.csr" "$OUT/$service.ext"
    echo "Сертификат для $service готов"
done

# Сервисы работают от непривилегированного пользователя и должны прочитать свой ключ.
chmod 644 "$OUT"/*.crt "$OUT"/*.key
# А ключ CA им не нужен вовсе: без него новый сертификат не выпустить.
chmod 600 "$OUT/ca.key"
