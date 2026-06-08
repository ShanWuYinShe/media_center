FROM node:22-alpine AS builder

WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-alpine

WORKDIR /app

RUN mkdir -p /cache /config \
    && chown -R node:node /cache /config

ENV HOST=0.0.0.0 \
    PORT=8787 \
    BACKEND_CACHE_DIR=/cache \
    BACKEND_CACHE_MAX_BYTES=53687091200

COPY --from=builder --chown=node:node /app/package.json ./package.json
COPY --from=builder --chown=node:node /app/node_modules ./node_modules
COPY --from=builder --chown=node:node /app/dist ./dist

VOLUME ["/cache", "/config"]
EXPOSE 8787

USER node
CMD ["node", "dist/src/index.js"]
