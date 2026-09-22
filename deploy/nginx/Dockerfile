ARG NGINX_VERSION=1.30.4

FROM nginx:${NGINX_VERSION}-alpine

ARG APK_MIRROR=mirrors.aliyun.com
RUN sed -i "s#dl-cdn.alpinelinux.org#${APK_MIRROR}#g" /etc/apk/repositories \
    && apk add --no-cache curl tzdata

# 启动前拉取 Cloudflare IP 段生成 real_ip include（官方镜像的
# /docker-entrypoint.d/ 机制按文件名顺序执行，20* 在 envsubst 30* 之前）
COPY 20-fetch-cloudflare-ips.sh /docker-entrypoint.d/20-fetch-cloudflare-ips.sh
COPY fetch-cloudflare-ips.sh /usr/local/bin/fetch-cloudflare-ips.sh
RUN chmod +x /docker-entrypoint.d/20-fetch-cloudflare-ips.sh /usr/local/bin/fetch-cloudflare-ips.sh

# MEDIA_DOMAIN 等环境变量由官方入口 envsubst 展开（仅替换已定义的环境变量，
# nginx 运行时变量 $remote_addr 等不受影响）
COPY default.conf.template /etc/nginx/templates/default.conf.template
