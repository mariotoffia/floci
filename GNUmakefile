# Fork-only targets for the teos branch. GNU make reads this file before Makefile,
# so upstream's Makefile stays untouched and its targets still work.
include Makefile

.PHONY: teos
teos: ## Rebuild teos (upstream main + my open PRs) and publish mariotoffia/floci, locally via act. MULTIARCH=1 for amd64+arm64
	@grep -qx .env .git/info/exclude 2>/dev/null || echo .env >> .git/info/exclude
	act workflow_dispatch -s GITHUB_TOKEN="$$(gh auth token)" --env MULTIARCH=$(MULTIARCH)

.PHONY: clear
clear: ## Resync this clone with the published teos, replanting any newer local tooling on top
	@git diff --quiet && git diff --cached --quiet || { echo "uncommitted changes: commit or stash them first"; exit 1; }
	git fetch origin teos
	@tooling=$$(git rev-parse HEAD); set -e; \
		git reset --hard FETCH_HEAD; \
		git checkout $$tooling -- .github/workflows/teos.yml .actrc GNUmakefile; \
		git diff --cached --quiet || git commit -q -m "ci: teos tooling"
