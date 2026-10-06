FROM linuxserver/swag:latest

LABEL maintainer="Rivelino Patrício <rivelinopatricio@gmail.com>"
LABEL description="Hardened NGINX Reverse Proxy with Integrated Let's Encrypt for Exegese AI"

# Remove NGINX version from HTTP response headers for fingerprinting prevention
RUN sed -i 's/# server_tokens off;/server_tokens off;/' /config/nginx/nginx.conf 2>/dev/null || true

RUN mkdir -p /config/nginx/site-confs

# Copy default site configuration for Exegese AI
COPY config/default /config/nginx/site-confs/default
