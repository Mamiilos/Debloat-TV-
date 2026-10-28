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
	$(PYTHON) scripts/tvconfig.py merge >/dev/null
	$(PYTHON) webos/build.py --config out/effective.properties

# Always rebuild: install.py reuses any FreeMyTV-webos.ipk it finds, which after a checkout is the wrong one.
install: build
	$(PYTHON) webos/install.py $(TV)

uninstall:
	$(PYTHON) webos/install.py $(TV) --uninstall

config-hash:
	@$(PYTHON) scripts/tvconfig.py hash

# Refuses a dirty tree, so the tag is exactly what went on the TV; tags only after the install succeeded.
release:
	@if [ -n "$$(git status --porcelain)" ]; then \
		echo 'Uncommitted changes: commit or stash them first, so the tag matches what goes on the TV.'; exit 1; fi
	$(MAKE) install TV=$(TV)
	@hash=$$($(PYTHON) scripts/tvconfig.py hash) || exit 1; \
	day=$$(date +%Y-%m-%d); n=1; \
	while git rev-parse -q --verify "refs/tags/tv-$$day-$$n" >/dev/null; do n=$$((n + 1)); done; \
	git tag -a "tv-$$day-$$n" -m "Installed on $(TV)" -m "config-sha256: $$hash" && echo "Tagged tv-$$day-$$n (config $$hash)"
