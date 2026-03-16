.PHONY: help test test-integration test-cover lint fmt check clean

# Default target
help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

test: ## Run unit tests (excludes integration)
	@./gradlew test

test-integration: ## Run integration tests (requires Go testserver)
	@./gradlew integrationTest

test-cover: ## Run tests with coverage report
	@./gradlew test jacocoTestReport

lint: ## Run ktlint and detekt checks
	@./gradlew ktlintCheck

fmt: ## Format source files with ktlint
	@./gradlew ktlintFormat

check: ## Run fmt-check, lint, and test (pre-commit gate)
	@echo "── fmt ──"
	@./gradlew ktlintCheck
	@echo "── test ──"
	@$(MAKE) --no-print-directory test
	@echo "── all passed ──"

clean: ## Remove build artifacts
	@./gradlew clean
