# media 站部署入口。版本唯一来源是 versions.env：GNU make include 导入后
# 由下方裸 export 整体导出，compose 变量替换与 build args（GO_VERSION 等）
# 即读到统一值；调用方显式传入时以传入值为准。

-include versions.env
export

COMPOSE := docker compose
GIT_VERSION ?= $(shell git -C .. describe --always --tags --dirty 2>/dev/null || echo dev)
export IMAGE_TAG ?= $(GIT_VERSION)

SERVICES := nginx media_server vod_extractor

.DEFAULT_GOAL := help
.PHONY: help $(SERVICES) build up restart logs ps

KNOWN_TARGETS := help build up restart logs ps

# 变长服务名目标的兜底规则（make build nginx 里的 "nginx" 不是 make 目标）。
# 必须校验：无守卫时拼错目标（make buil nginx）会静默成功退出 0。
%:
	@if ! echo " $(KNOWN_TARGETS) $(SERVICES) " | grep -q " $@ "; then \
		echo "make: *** 未知目标 '$@'。可用: make {build|up|restart|logs} <服务> 或 $(KNOWN_TARGETS)"; \
		exit 1; \
	fi

help: ## 显示帮助
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36mmake %-22s\033[0m %s\n",$$1,$$2}'
	@echo '  变长参数用法: make build <svc...> / make up <svc...> / make restart <svc...> / make logs <svc...>'

build: ## 构建指定服务镜像: make build nginx media_server
	$(COMPOSE) build $(filter-out $@,$(MAKECMDGOALS))

up: ## 重建指定容器(不牵连依赖): make up nginx
	$(COMPOSE) up -d --no-deps $(filter-out $@,$(MAKECMDGOALS))

restart: ## 重启指定容器: make restart media_server
	$(COMPOSE) restart $(filter-out $@,$(MAKECMDGOALS))

logs: ## 跟踪指定服务日志: make logs nginx
	$(COMPOSE) logs -f $(filter-out $@,$(MAKECMDGOALS))

ps: ## 查看服务状态
	$(COMPOSE) ps
