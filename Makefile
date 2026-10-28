# Free My TV on the LG TV (webOS Developer Mode).
#   make build                  FreeMyTV-webos.ipk from tv.properties and config.properties
#   make install [TV=<ip>]      build, then install and open it on the TV
#   make uninstall [TV=<ip>]    remove it from the TV
#   make release [TV=<ip>]      build, install, then tag the commit tv-YYYY-MM-DD-N with the settings' hash
#   make config-hash            the hash of the settings a build would use (secrets left out)
# Settings are in tv.properties (tracked); the keys stay in config.properties (gitignored).
# Roll back: git checkout tv-YYYY-MM-DD-N && make install (code and settings both come back; the keys are today's).

TV ?= 192.168.0.4
PYTHON ?= python3

.PHONY: build install uninstall release config-hash

build:
	BUILD_TAG='$(BUILD_TAG)' DEVMODE_UNTIL='$(DEVMODE_UNTIL)' $(PYTHON) scripts/tvconfig.py merge >/dev/null
	$(PYTHON) webos/build.py --config out/effective.properties

# Always rebuild: install.py reuses any FreeMyTV-webos.ipk it finds, which after a checkout is the wrong one.
# The build carries Developer Mode's end (scripts/devmode.py asks the TV and LG; left out when it can't tell).
install:
	@mkdir -p out; $(PYTHON) scripts/devmode.py $(TV) > out/devmode-until || true
	$(MAKE) build BUILD_TAG='$(BUILD_TAG)' DEVMODE_UNTIL="$$(cat out/devmode-until 2>/dev/null)"
	$(PYTHON) webos/install.py $(TV)

uninstall:
	$(PYTHON) webos/install.py $(TV) --uninstall

config-hash:
	@$(PYTHON) scripts/tvconfig.py hash

# Refuses a dirty tree, so the tag is exactly what went on the TV. The tag's name is chosen first (About this TV shows
# it), and it's made only after the install succeeded. The tagging stays off the $(MAKE) line: make runs that line even
# under -n, so a dry run would tag.
release:
	@if [ -n "$$(git status --porcelain)" ]; then \
		echo 'Uncommitted changes: commit or stash them first, so the tag matches what goes on the TV.'; exit 1; fi
	@mkdir -p out; day=$$(date +%Y-%m-%d); n=1; \
	while git rev-parse -q --verify "refs/tags/tv-$$day-$$n" >/dev/null; do n=$$((n + 1)); done; \
	echo "tv-$$day-$$n" > out/release-tag
	$(MAKE) install TV=$(TV) BUILD_TAG="$$(cat out/release-tag 2>/dev/null)"
	@tag=$$(cat out/release-tag); hash=$$($(PYTHON) scripts/tvconfig.py hash) || exit 1; \
	git tag -a "$$tag" -m "Installed on $(TV)" -m "config-sha256: $$hash" && rm out/release-tag && echo "Tagged $$tag (config $$hash)"
