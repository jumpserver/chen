SHELL := /bin/bash
.DEFAULT_GOAL := help
.NOTPARALLEL:

DOCKER_COMPOSE ?= docker compose
COMPOSE_PARALLEL_LIMIT ?= 1
WISP_IMAGE_REPOSITORY ?= ghcr.io/jumpserver/wisp
WISP_BRANCH ?=
export WISP_BRANCH
WISP_TAG ?= $(shell ./scripts/resolve-wisp-tag.sh)
WISP_IMAGE ?= $(WISP_IMAGE_REPOSITORY):$(WISP_TAG)
WISP_PULL_POLICY ?= missing

export COMPOSE_PARALLEL_LIMIT
export WISP_IMAGE_REPOSITORY
export WISP_TAG
export WISP_IMAGE
export WISP_PULL_POLICY
export WISP_SOURCE

DEV_COMPOSE_FILES := -f docker-compose.yml -f docker-compose.wisp-dev.yml
ifneq ($(strip $(WISP_SOURCE)),)
DEV_COMPOSE_FILES += -f docker-compose.wisp-source.yml
endif
DEV_COMPOSE := $(DOCKER_COMPOSE) $(DEV_COMPOSE_FILES)

.PHONY: help dev run dev-run dev-up dev-wisp dev-wisp-image dev-logs dev-ps dev-config dev-down proto-sync

help:
	@printf '%s\n' \
		'Chen development commands:' \
		'  make dev                 Run in the foreground (make dev run also works)' \
		'  make dev-up              Build and run in the background' \
		'  make dev-wisp            Rebuild/recreate only Wisp' \
		'  make dev-wisp-image      Show the resolved Wisp image' \
		'  make dev-logs            Follow Chen and Wisp logs' \
		'  make dev-ps              Show development services' \
		'  make dev-config          Render the merged Compose configuration' \
		'  make dev-down            Stop and remove development services' \
		'  make proto-sync          Copy generated Java protobuf files from Wisp' \
		'' \
		'Wisp source mode:' \
		'  make dev WISP_SOURCE=/absolute/path/to/wisp' \
		'' \
		'Wisp image mode:' \
		'  Wisp tag defaults to the current Chen tracking branch' \
		'  make dev WISP_TAG=latest' \
		'  make dev WISP_IMAGE=registry/wisp:tag WISP_PULL_POLICY=always'

# Both spellings are supported, including one invocation written as
# `make dev run`. GNU Make updates their shared prerequisite only once.
dev run: dev-run
	@:

dev-run:
	@$(DEV_COMPOSE) up --build

dev-up:
	@$(DEV_COMPOSE) up -d --build

dev-wisp:
	@$(DEV_COMPOSE) up -d --build --no-deps wisp

dev-wisp-image:
	@printf '%s\n' '$(WISP_IMAGE)'

dev-logs:
	@$(DEV_COMPOSE) logs -f chen-backend wisp

dev-ps:
	@$(DEV_COMPOSE) ps

dev-config:
	@$(DEV_COMPOSE) config

dev-down:
	@$(DEV_COMPOSE) down

proto-sync:
	@./scripts/sync-wisp-proto.sh
