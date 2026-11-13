PREFIX  ?= /usr/local
CC      ?= cc
CFLAGS  ?= -O2 -pipe
CFLAGS  += -std=c11 -Wall -Wextra -Wpedantic
LDFLAGS ?= -Wl,-O1,--as-needed
LDLIBS  += -lm

ember: ember.c
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ $< $(LDLIBS)

install: ember
	install -Dm755 ember $(DESTDIR)$(PREFIX)/bin/ember
	install -Dm644 contrib/ember.service $(DESTDIR)$(PREFIX)/lib/systemd/user/ember.service
	install -Dm644 contrib/config.example $(DESTDIR)$(PREFIX)/share/doc/ember/config.example
	install -Dm644 LICENSE $(DESTDIR)$(PREFIX)/share/licenses/ember/LICENSE

uninstall:
	rm -f $(DESTDIR)$(PREFIX)/bin/ember $(DESTDIR)$(PREFIX)/lib/systemd/user/ember.service
	rm -rf $(DESTDIR)$(PREFIX)/share/doc/ember $(DESTDIR)$(PREFIX)/share/licenses/ember

clean:
	rm -f ember

.PHONY: install uninstall clean
