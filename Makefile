SHELL := /bin/bash
.DEFAULT_GOAL := help
.NOTPARALLEL:

MAVEN ?= mvn
LOCAL_REVISION ?= 0.0.1
LOCAL_MAVEN_TEST_ARGS ?= -Dmaven.test.skip=true
LOCAL_JAVA_HOME ?= $(shell if [[ -x /usr/libexec/java_home ]]; then /usr/libexec/java_home -v 21 2>/dev/null; elif [[ -n "$$JAVA_HOME" ]]; then printf '%s' "$$JAVA_HOME"; fi)
LOCAL_JAVA_ENV := $(if $(strip $(LOCAL_JAVA_HOME)),JAVA_HOME="$(LOCAL_JAVA_HOME)")

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
		'  make run                 Build and run Chen locally' \
		'  make dev                 Run Chen and Wisp with Compose in the foreground' \
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

dev: dev-run
	@:

dev-run:
	@$(DEV_COMPOSE) up --build

run:
	@$(LOCAL_JAVA_ENV) $(MAVEN) -U \
		-Drevision=$(LOCAL_REVISION) \
		$(LOCAL_MAVEN_TEST_ARGS) \
		-Dmaven.antrun.skip=true \
		clean org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install
	@cd backend/web && $(LOCAL_JAVA_ENV) $(MAVEN) \
		-Drevision=$(LOCAL_REVISION) \
		$(LOCAL_MAVEN_TEST_ARGS) \
		spring-boot:run \
		-Dmaven.antrun.skip=true \
		-Dspring-boot.run.arguments="--spring.config.additional-location=file:$(CURDIR)/config/ --driver.driver-path=$(CURDIR)/drivers"

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
