#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${CERTBOT_EMAIL:-}" || -z "${CERTBOT_DOMAINS:-}" ]]; then
  echo "CERTBOT_EMAIL and CERTBOT_DOMAINS must be set in the environment." >&2
  exit 1
fi

bootstrap_conf="deploy/nginx-bootstrap.conf"
prod_conf="deploy/nginx.conf"
backup_conf="deploy/nginx.conf.bak"

if [[ ! -f "${bootstrap_conf}" ]]; then
  echo "Missing ${bootstrap_conf}. Did you sync deploy files to the server?" >&2
  exit 1
fi

if [[ -f "${prod_conf}" ]]; then
  cp "${prod_conf}" "${backup_conf}"
fi

cp "${bootstrap_conf}" "${prod_conf}"
docker compose -f docker-compose.prod.yml up -d nginx

docker compose -f docker-compose.prod.yml run --rm --entrypoint certbot certbot \
  certonly --webroot -w /var/www/certbot \
  --email "${CERTBOT_EMAIL}" \
  --agree-tos --no-eff-email \
  $(printf -- " -d %s" ${CERTBOT_DOMAINS//,/ })

if [[ -f "${backup_conf}" ]]; then
  mv "${backup_conf}" "${prod_conf}"
fi

docker compose -f docker-compose.prod.yml up -d nginx
