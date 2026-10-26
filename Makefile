SHELL := /bin/bash
.DEFAULT_GOAL := help
.NOTPARALLEL:

GO ?= go
VERSION ?= dev
PLATFORM ?= linux/$(shell $(GO) env GOARCH)
WITH_DB2 ?= 0
APT_MIRROR ?= https://deb.debian.org
DB2_CLI_URL ?=
BASE_VERSION ?= go1.26.3-wisp0.2.15
VARIANT := $(if $(filter 1,$(WITH_DB2)),-db2,)
CHEN_BASE_IMAGE ?= jumpserver/chen-base:$(BASE_VERSION)$(VARIANT)
CHEN_IMAGE ?= jumpserver/chen:$(VERSION)$(VARIANT)-ce
CHEN_EE_IMAGE ?= jumpserver/chen:$(VERSION)$(VARIANT)-ee
DOCKER_COMPOSE ?= docker compose
COMPOSE_FILES ?= -f docker-compose.yml
GO_BUILD_TAGS ?=
BACKENDS ?= data/standalone_backends.yml
WEB_DIR ?= ../luna/.output/public
export CHEN_BASE_IMAGE CHEN_IMAGE VERSION WITH_DB2 GO_BUILD_TAGS

.PHONY: help build test vet dev single run dev-run dev-up dev-logs dev-ps dev-config dev-down image-base image image-ee package
help:
	@printf '%s\n' \
	  'make build/test/vet       Build and check the Go backend (CGO required)' \
	  'make dev                  Build and run local Chen + pinned Wisp; Core must be available' \
	  'make single               Run without Core/Wisp; BACKENDS=... WEB_DIR=...' \
	  'make image                Build formal base -> Go CE image for PLATFORM=...' \
	  'make image-ee             Build base -> CE -> EE image' \
	  'make image WITH_DB2=1 PLATFORM=linux/amd64  Include IBM CLI / Db2' \
	  'make run / dev-up         Run the managed image in foreground / background' \
	  'make dev-down             Stop this Compose project (keep data volumes)' \
	  'make package              Export current CHEN_IMAGE as a native release archive'
build:
	@mkdir -p data/bin
	CGO_ENABLED=1 $(GO) build -tags='$(GO_BUILD_TAGS)' -trimpath -ldflags='-X main.version=$(VERSION)' -o data/bin/chen-go ./cmd/chen-go
test:
	CGO_ENABLED=1 $(GO) test -race -tags='$(GO_BUILD_TAGS)' ./...
vet:
	CGO_ENABLED=1 $(GO) vet -tags='$(GO_BUILD_TAGS)' ./...
dev:
	@./start.sh managed
single:
	@./start.sh single --backends '$(BACKENDS)' --web-dir '$(WEB_DIR)'
image-base:
	docker build --platform '$(PLATFORM)' -f Dockerfile-base --build-arg APT_MIRROR='$(APT_MIRROR)' $(if $(strip $(DB2_CLI_URL)),--build-arg DB2_CLI_URL='$(DB2_CLI_URL)',) --build-arg WITH_DB2='$(WITH_DB2)' -t '$(CHEN_BASE_IMAGE)' .
image: image-base
	docker build --platform '$(PLATFORM)' -f Dockerfile --build-arg APT_MIRROR='$(APT_MIRROR)' --build-arg CHEN_BASE_IMAGE='$(CHEN_BASE_IMAGE)' --build-arg VERSION='$(VERSION)' --build-arg WITH_DB2='$(WITH_DB2)' -t '$(CHEN_IMAGE)' .
image-ee: image
	docker build --platform '$(PLATFORM)' -f Dockerfile-ee --build-arg CHEN_CE_IMAGE='$(CHEN_IMAGE)' -t '$(CHEN_EE_IMAGE)' .
package:
	@./scripts/package-image.sh '$(CHEN_IMAGE)' '$(VERSION)$(VARIANT)' '$(PLATFORM)'
run: dev-run
dev-run: image
	@$(DOCKER_COMPOSE) $(COMPOSE_FILES) up --no-build
dev-up: image
	@$(DOCKER_COMPOSE) $(COMPOSE_FILES) up -d --no-build
dev-logs:
	@$(DOCKER_COMPOSE) $(COMPOSE_FILES) logs -f
dev-ps:
	@$(DOCKER_COMPOSE) $(COMPOSE_FILES) ps
dev-config:
	@$(DOCKER_COMPOSE) $(COMPOSE_FILES) config --quiet
dev-down:
	@$(DOCKER_COMPOSE) $(COMPOSE_FILES) down
