# ==============================================================================
# Exegese AI - Reverse Proxy (SWAG: NGINX + Certbot + fail2ban, LinuxServer.io)
#
# Nothing is copied into /config here: SWAG populates /config at runtime from its defaults (it is the
# proxy_config volume), so files baked into it at build time never take effect. The Exegese AI site
# (docker/proxy/config/default) is bind-mounted by docker-compose.yml as
# /config/nginx/site-confs/default.conf, and SWAG's nginx.conf already sets "server_tokens off".
# This file only pins the upstream image so the "exegese-ai-proxy" image used by compose still builds.
# ==============================================================================
FROM linuxserver/swag:5.8.0

LABEL maintainer="Rivelino Patrício <rivelinopatricio@gmail.com>"
LABEL description="Hardened NGINX Reverse Proxy with Integrated Let's Encrypt for Exegese AI"
