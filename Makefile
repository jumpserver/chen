SHELL := /bin/bash
.DEFAULT_GOAL := help
.NOTPARALLEL:

GO ?= go
VERSION ?= dev
PLATFORM ?= linux/$(shell $(GO) env GOARCH)
APT_MIRROR ?= https://deb.debian.org
BASE_VERSION ?= 20261104_090447
CHEN_BASE_IMAGE ?= jumpserver/chen-base:$(BASE_VERSION)
CHEN_IMAGE ?= jumpserver/chen:$(VERSION)-ce
CHEN_EE_IMAGE ?= jumpserver/chen:$(VERSION)-ee
DOCKER_COMPOSE ?= docker compose
COMPOSE_FILES ?= -f docker-compose.yml
GO_BUILD_TAGS ?=
BACKENDS ?= data/standalone_backends.yml
WEB_DIR ?= ../luna/.output/public
export CHEN_BASE_IMAGE CHEN_IMAGE VERSION GO_BUILD_TAGS

.PHONY: help build vet dev single run dev-run dev-up dev-logs dev-ps dev-config dev-down image-base image image-ee package
help:
	@printf '%s\n' \
	  'make build/vet            Build and check the Go backend (CGO required)' \
	  'make dev                  Build and run local Chen + pinned Wisp; Core must be available' \
	  'make single               Run without Core/Wisp; BACKENDS=... WEB_DIR=...' \
	  'make image                Build formal base -> Go CE image for PLATFORM=...' \
	  'make image-ee             Build base -> CE -> EE image' \
	  'make run / dev-up         Run the managed image in foreground / background' \
	  'make dev-down             Stop this Compose project (keep data volumes)' \
	  'make package              Export current CHEN_IMAGE as a native release archive'
build:
	@mkdir -p data/bin
	CGO_ENABLED=1 $(GO) build -tags='$(GO_BUILD_TAGS)' -trimpath -ldflags='-X main.version=$(VERSION)' -o data/bin/chen-go ./cmd/chen-go
vet:
	CGO_ENABLED=1 $(GO) vet -tags='$(GO_BUILD_TAGS)' ./...
dev:
	@./start.sh managed
single:
	@./start.sh single --backends '$(BACKENDS)' --web-dir '$(WEB_DIR)'
image-base:
	docker build --platform '$(PLATFORM)' -f Dockerfile-base --build-arg APT_MIRROR='$(APT_MIRROR)' -t '$(CHEN_BASE_IMAGE)' .
image: image-base
	docker build --platform '$(PLATFORM)' -f Dockerfile --build-arg APT_MIRROR='$(APT_MIRROR)' --build-arg CHEN_BASE_IMAGE='$(CHEN_BASE_IMAGE)' --build-arg VERSION='$(VERSION)' -t '$(CHEN_IMAGE)' .
image-ee: image
	docker build --platform '$(PLATFORM)' -f Dockerfile-ee --build-arg CHEN_CE_IMAGE='$(CHEN_IMAGE)' -t '$(CHEN_EE_IMAGE)' .
package:
	@./scripts/package-image.sh '$(CHEN_IMAGE)' '$(VERSION)' '$(PLATFORM)'
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
