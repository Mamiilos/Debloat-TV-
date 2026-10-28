# Free My TV on the LG TV (webOS Developer Mode).
#   make build                  FreeMyTV-webos.ipk from config.properties
#   make install [TV=<ip>]      build, then install and open it on the TV
#   make uninstall [TV=<ip>]    remove it from the TV
#   make release [TV=<ip>]      build, install, then tag the commit tv-YYYY-MM-DD-N
# Roll back: git checkout tv-YYYY-MM-DD-N && make install
# config.properties isn't versioned, so a rollback builds the old code with today's settings.

TV ?= 192.168.0.4
PYTHON ?= python3

.PHONY: build install uninstall release

build:
	$(PYTHON) webos/build.py

# Always rebuild: install.py reuses any FreeMyTV-webos.ipk it finds, which after a checkout is the wrong one.
install: build
	$(PYTHON) webos/install.py $(TV)

uninstall:
	$(PYTHON) webos/install.py $(TV) --uninstall

# Refuses a dirty tree, so the tag is exactly what went on the TV; tags only after the install succeeded.
release:
	@if [ -n "$$(git status --porcelain)" ]; then \
		echo 'Uncommitted changes: commit or stash them first, so the tag matches what goes on the TV.'; exit 1; fi
	$(MAKE) install TV=$(TV)
	@day=$$(date +%Y-%m-%d); n=1; \
	while git rev-parse -q --verify "refs/tags/tv-$$day-$$n" >/dev/null; do n=$$((n + 1)); done; \
	git tag -a "tv-$$day-$$n" -m "Installed on $(TV)" && echo "Tagged tv-$$day-$$n"
